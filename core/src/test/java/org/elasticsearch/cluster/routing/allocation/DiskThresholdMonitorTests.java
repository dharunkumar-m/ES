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

import org.elasticsearch.Version;
import org.elasticsearch.cluster.*;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.LocalTransportAddress;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.cluster.routing.ShardRoutingState.INITIALIZING;
public class DiskThresholdMonitorTests extends ESAllocationTestCase {

    public void testMarkFloodStageIndicesReadOnly() {
        AllocationService allocation = createAllocationService(Settings.builder()
            .put("cluster.routing.allocation.node_concurrent_recoveries", 10).build());
        MetaData metaData = MetaData.builder()
            .put(IndexMetaData.builder("test_1").settings(settings(Version.CURRENT)
                .put("index.routing.allocation.require._id", "node1")).numberOfShards(1).numberOfReplicas(0))
            .put(IndexMetaData.builder("test_2").settings(settings(Version.CURRENT)
                .put("index.routing.allocation.require._id", "node2")).numberOfShards(1).numberOfReplicas(0))
            .build();
        RoutingTable routingTable = RoutingTable.builder()
            .addAsNew(metaData.index("test_1"))
            .addAsNew(metaData.index("test_2"))
            .build();

        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metaData(metaData).routingTable(routingTable).build();
        logger.info("adding two nodes and performing rerouting");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder().add(newNode("node1"))
            .add(newNode("node2"))).build();
        clusterState = allocation.reroute(clusterState, "reroute");
        logger.info("start primary shard");
        clusterState = allocation.applyStartedShards(clusterState, clusterState.getRoutingNodes().shardsWithState(INITIALIZING));
        ClusterState finalState = clusterState;

        AtomicReference<Set<String>> indicesToMarkReadOnly = new AtomicReference<>();
        AtomicReference<Set<String>> indicesToRelease = new AtomicReference<>();
        AtomicReference<Set<String>> nodesReadOnly = new AtomicReference<>();

        ClusterService clusterService = new ClusterService(Settings.EMPTY,
            new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS), null, () ->
            new DiscoveryNode(UUIDs.randomBase64UUID(), LocalTransportAddress.buildUnique(), Version.CURRENT));

        DiskThresholdMonitor monitor = new DiskThresholdMonitor(Settings.EMPTY, () -> finalState,
            new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS), null, clusterService) {
            @Override
            protected void reroute() {
            }

            @Override
            protected void updateIndicesReadOnly(Set<String> indicesToUpdate, boolean readOnly) {
                if (readOnly) {
                    assertTrue(indicesToMarkReadOnly.compareAndSet(null, indicesToUpdate));
                } else {
                    assertTrue(indicesToRelease.compareAndSet(null, indicesToUpdate));
                }
            }

            @Override
            protected void updateReadOnlyNodes(Set<String> readOnlyNodes) {
                assertTrue(nodesReadOnly.compareAndSet(null, readOnlyNodes));
            }
        };

        //One Node Disk is below flood stage watermark
        ImmutableOpenMap.Builder<String, DiskUsage> builder = ImmutableOpenMap.builder();
        builder.put("node1", new DiskUsage("node1","node1", "/foo/bar", 100, between(0, 4)));
        builder.put("node2", new DiskUsage("node2","node2", "/foo/bar", 100, between(10, 100)));
        monitor.onNewInfo(new ClusterInfo(builder.build(), null, null, null));
        assertEquals(new HashSet<>(Arrays.asList("test_1")), indicesToMarkReadOnly.get());
        assertNull(indicesToRelease.get());
        assertEquals(new HashSet<>(Arrays.asList("node1")), nodesReadOnly.get());

        indicesToMarkReadOnly.set(null);
        indicesToRelease.set(null);
        nodesReadOnly.set(null);

        //Both Node Disk is below flood stage watermark
        builder = ImmutableOpenMap.builder();
        builder.put("node1", new DiskUsage("node1","node1", "/foo/bar", 100, between(0, 4)));
        builder.put("node2", new DiskUsage("node2","node2", "/foo/bar", 100, between(0, 4)));
        monitor.onNewInfo(new ClusterInfo(builder.build(), null, null, null));
        assertEquals(new HashSet<>(Arrays.asList("test_1", "test_2")), indicesToMarkReadOnly.get());
        assertNull(indicesToRelease.get());
        assertEquals(new HashSet<>(Arrays.asList("node1", "node2")), nodesReadOnly.get());

        indicesToMarkReadOnly.set(null);
        indicesToRelease.set(null);
        nodesReadOnly.set(null);

        //Setting indices of one node to read-only
        IndexMetaData indexMetaData = IndexMetaData.builder(clusterState.metaData().index("test_1")).settings(Settings.builder()
            .put(clusterState.metaData()
                .index("test_1").getSettings())
            .put(IndexMetaData.INDEX_BLOCKS_READ_ONLY_ALLOW_DELETE_SETTING.getKey(), true)).build();
        ClusterState clusterStateWithBlocks = ClusterState.builder(clusterState).metaData(MetaData.builder(clusterState.metaData())
                .put(indexMetaData, true).build())
            .blocks(ClusterBlocks.builder().addBlocks(indexMetaData).build()).build();
        assertTrue(clusterStateWithBlocks.blocks().indexBlocked(ClusterBlockLevel.WRITE, "test_1"));

        DiskThresholdMonitor newMonitor = new DiskThresholdMonitor(Settings.EMPTY, () -> clusterStateWithBlocks,
            new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS), null, clusterService) {
            @Override
            protected void reroute() {
            }

            @Override
            protected void updateIndicesReadOnly(Set<String> indicesToUpdate, boolean readOnly) {
                if (readOnly) {
                    assertTrue(indicesToMarkReadOnly.compareAndSet(null, indicesToUpdate));
                } else {
                    assertTrue(indicesToRelease.compareAndSet(null, indicesToUpdate));
                }
            }

            @Override
            protected void updateReadOnlyNodes(Set<String> readOnlyNodes) {
                assertTrue(nodesReadOnly.compareAndSet(null, readOnlyNodes));
            }
        };

        //Setting one node disk space beyond flood stage, in which the indices were read-only
        builder = ImmutableOpenMap.builder();
        builder.put("node1", new DiskUsage("node1","node1", "/foo/bar", 100, between(10, 100)));
        builder.put("node2", new DiskUsage("node2","node2", "/foo/bar", 100, between(0, 4)));
        newMonitor.onNewInfo(new ClusterInfo(builder.build(), null, null, null));
        assertEquals(new HashSet<>(Arrays.asList("test_2")), indicesToMarkReadOnly.get());
        assertEquals(new HashSet<>(Arrays.asList("test_1")), indicesToRelease.get());
        assertEquals(new HashSet<>(Arrays.asList("node2")), nodesReadOnly.get());
    }
}
