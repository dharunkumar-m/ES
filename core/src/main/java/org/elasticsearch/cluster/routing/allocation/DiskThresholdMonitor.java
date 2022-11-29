/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster.routing.allocation;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.carrotsearch.hppc.ObjectLookupContainer;
import com.carrotsearch.hppc.cursors.ObjectObjectCursor;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.*;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.RoutingNodes;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.set.Sets;

/**
 * Listens for a node to go over the high watermark and kicks off an empty
 * reroute if it does. Also responsible for logging about nodes that have
 * passed the disk watermarks
 */
public class DiskThresholdMonitor extends AbstractComponent {
    private final DiskThresholdSettings diskThresholdSettings;
    private final Client client;
    private final Set<String> nodeHasPassedWatermark = Sets.newConcurrentHashSet();
    private final Supplier<ClusterState> clusterStateSupplier;
    private long lastRunNS;

    public DiskThresholdMonitor(Settings settings, Supplier<ClusterState> clusterStateSupplier, ClusterSettings clusterSettings,
                                Client client) {
        super(settings);
        this.clusterStateSupplier = clusterStateSupplier;
        this.diskThresholdSettings = new DiskThresholdSettings(settings, clusterSettings);
        this.client = client;
    }

    /**
     * Warn about the given disk usage if the low or high watermark has been passed
     */
    private void warnAboutDiskIfNeeded(DiskUsage usage) {
        // Check absolute disk values
        if (usage.getFreeBytes() < diskThresholdSettings.getFreeBytesThresholdFloodStage().getBytes()) {
            logger.warn("floodstage disk watermark [{}] exceeded on {}, all indices on this node will marked read-only",
                diskThresholdSettings.getFreeBytesThresholdFloodStage(), usage);
        } else if (usage.getFreeBytes() < diskThresholdSettings.getFreeBytesThresholdHigh().getBytes()) {
            logger.warn("high disk watermark [{}] exceeded on {}, shards will be relocated away from this node",
                diskThresholdSettings.getFreeBytesThresholdHigh(), usage);
        } else if (usage.getFreeBytes() < diskThresholdSettings.getFreeBytesThresholdLow().getBytes()) {
            logger.info("low disk watermark [{}] exceeded on {}, replicas will not be assigned to this node",
                diskThresholdSettings.getFreeBytesThresholdLow(), usage);
        }

        // Check percentage disk values
        if (usage.getFreeDiskAsPercentage() < diskThresholdSettings.getFreeDiskThresholdHigh()) {
            logger.warn("floodstage disk watermark [{}] exceeded on {}, all indices on this node will marked read-only",
                Strings.format1Decimals(100.0 - diskThresholdSettings.getFreeDiskThresholdFloodStage(), "%"), usage);
        } else if (usage.getFreeDiskAsPercentage() < diskThresholdSettings.getFreeDiskThresholdHigh()) {
            logger.warn("high disk watermark [{}] exceeded on {}, shards will be relocated away from this node",
                Strings.format1Decimals(100.0 - diskThresholdSettings.getFreeDiskThresholdHigh(), "%"), usage);
        } else if (usage.getFreeDiskAsPercentage() < diskThresholdSettings.getFreeDiskThresholdLow()) {
            logger.info("low disk watermark [{}] exceeded on {}, replicas will not be assigned to this node",
                Strings.format1Decimals(100.0 - diskThresholdSettings.getFreeDiskThresholdLow(), "%"), usage);
        }
    }


    public void onNewInfo(ClusterInfo info) {
        ImmutableOpenMap<String, DiskUsage> usages = info.getNodeLeastAvailableDiskUsages();
        if (usages != null) {
            boolean reroute = false;
            String explanation = "";

            // Garbage collect nodes that have been removed from the cluster
            // from the map that tracks watermark crossing
            ObjectLookupContainer<String> nodes = usages.keys();
            for (String node : nodeHasPassedWatermark) {
                if (nodes.contains(node) == false) {
                    nodeHasPassedWatermark.remove(node);
                }
            }
            ClusterState state = clusterStateSupplier.get();
            RoutingNodes routingNodes = state.getRoutingNodes();
            Set<String> indicesToMarkReadOnly = new HashSet<>();
            Set<String> indicesNotToAutoRelease = new HashSet<>();
            markNodesMissingUsageIneligibleForRelease(routingNodes, usages, indicesNotToAutoRelease);
            for (ObjectObjectCursor<String, DiskUsage> entry : usages) {
                String node = entry.key;
                DiskUsage usage = entry.value;
                String nodeName = usage.getNodeName();
                warnAboutDiskIfNeeded(usage);
                RoutingNode routingNode = routingNodes.node(node);
                if (usage.getFreeBytes() < diskThresholdSettings.getFreeBytesThresholdFloodStage().getBytes() ||
                    usage.getFreeDiskAsPercentage() < diskThresholdSettings.getFreeDiskThresholdFloodStage()) {
                    if (routingNode != null) { // this might happen if we haven't got the full cluster-state yet?!
                        for (ShardRouting routing : routingNode) {
                            String indexName = routing.index().getName();
                            indicesToMarkReadOnly.add(indexName);
                            indicesNotToAutoRelease.add(indexName);
                        }
                    }
                } else if (usage.getFreeBytes() < diskThresholdSettings.getFreeBytesThresholdHigh().getBytes() ||
                    usage.getFreeDiskAsPercentage() < diskThresholdSettings.getFreeDiskThresholdHigh()) {
                    if ((System.nanoTime() - lastRunNS) > diskThresholdSettings.getRerouteInterval().nanos()) {
                        lastRunNS = System.nanoTime();
                        reroute = true;
                        explanation = "high disk watermark exceeded on one or more nodes";
                    } else {
                        logger.debug("high disk watermark exceeded on {} but an automatic reroute has occurred " +
                                "in the last [{}], skipping reroute",
                            node, diskThresholdSettings.getRerouteInterval());
                    }
                    nodeHasPassedWatermark.add(node);
                } else if (usage.getFreeBytes() < diskThresholdSettings.getFreeBytesThresholdLow().getBytes() ||
                    usage.getFreeDiskAsPercentage() < diskThresholdSettings.getFreeDiskThresholdLow()) {
                    nodeHasPassedWatermark.add(node);
                } else {
                    if (nodeHasPassedWatermark.contains(node)) {
                        // The node has previously been over the high or
                        // low watermark, but is no longer, so we should
                        // reroute so any unassigned shards can be allocated
                        // if they are able to be
                        if ((System.nanoTime() - lastRunNS) > diskThresholdSettings.getRerouteInterval().nanos()) {
                            lastRunNS = System.nanoTime();
                            reroute = true;
                            explanation = "one or more nodes has gone under the high or low watermark";
                            nodeHasPassedWatermark.remove(node);
                        } else {
                            logger.debug("{} has gone below a disk threshold, but an automatic reroute has occurred " +
                                    "in the last [{}], skipping reroute",
                                node, diskThresholdSettings.getRerouteInterval());
                        }
                    }
                }
            }
            if (reroute) {
                logger.info("rerouting shards: [{}]", explanation);
                reroute();
            }

            Set<String> indicesToAutoRelease = StreamSupport.stream(state.routingTable().indicesRouting()
                    .spliterator(), false)
                .map(c -> c.key)
                .filter(index -> indicesNotToAutoRelease.contains(index) == false)
                .filter(index -> state.getBlocks().hasIndexBlock(index, IndexMetaData.INDEX_READ_ONLY_ALLOW_DELETE_BLOCK))
                .collect(Collectors.toSet());

            if (!indicesToAutoRelease.isEmpty()) {
                logger.info("releasing read-only-allow-delete block on indices: [{}]", indicesToAutoRelease);
                updateIndicesReadOnly(indicesToAutoRelease, false);
            }

            indicesToMarkReadOnly.removeIf(index -> state.getBlocks().indexBlocked(ClusterBlockLevel.WRITE, index));
            if (!indicesToMarkReadOnly.isEmpty()) {
                updateIndicesReadOnly(indicesToMarkReadOnly,true);
            }
        }
    }

    protected void reroute() {
        // Execute an empty reroute, but don't block on the response
        client.admin().cluster().prepareReroute().execute();
    }

    private void markNodesMissingUsageIneligibleForRelease(RoutingNodes routingNodes, ImmutableOpenMap<String, DiskUsage> usages,
                                                           Set<String> indicesToMarkIneligibleForAutoRelease) {
        for (RoutingNode routingNode : routingNodes) {
            if (usages.containsKey(routingNode.nodeId()) == false) {
                if (routingNode != null) {
                    for (ShardRouting routing : routingNode) {
                        String indexName = routing.index().getName();
                        indicesToMarkIneligibleForAutoRelease.add(indexName);
                    }
                }
            }
        }

    }

    protected void updateIndicesReadOnly(Set<String> indicesToUpdate, boolean readOnly) {
        // set read-only block but don't block on the response
        Settings readOnlySettings = readOnly ? Settings.builder().put(IndexMetaData.SETTING_READ_ONLY_ALLOW_DELETE, Boolean.TRUE.toString()).build() :
                                    Settings.builder().putNull(IndexMetaData.SETTING_READ_ONLY_ALLOW_DELETE).build();
        client.admin().indices().prepareUpdateSettings(indicesToUpdate.toArray(Strings.EMPTY_ARRAY)).setSettings(readOnlySettings)
            .execute(new ActionListener<UpdateSettingsResponse>() {
                @Override
                public void onResponse(UpdateSettingsResponse r) {
                    logger.info("setting indices [{}] read-only succeed [{}]", readOnly, r);
                }

                @Override
                public void onFailure(Exception e) {
                    logger.info(new ParameterizedMessage("setting indices [{}] read-only failed", readOnly), e);
                }
            });
    }

}
