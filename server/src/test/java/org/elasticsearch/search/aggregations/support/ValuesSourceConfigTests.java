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

package org.elasticsearch.search.aggregations.support;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.index.fielddata.SortedBinaryDocValues;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.MapperServiceTestCase;
import org.elasticsearch.index.mapper.TypeFieldMapper;

import java.io.IOException;

// TODO: This whole set of tests needs to be rethought.
public class ValuesSourceConfigTests extends MapperServiceTestCase {
    public void testKeyword() throws Exception {
        MapperService mapperService = createMapperService(fieldMapping(b -> b.field("type", "keyword")));
        withAggregationContext(mapperService, org.elasticsearch.common.collect.List.of(source(b -> b.field("field", "abc"))), context -> {
            ValuesSourceConfig config;
            config = ValuesSourceConfig.resolve(context, null, "field", null, null, null, null, CoreValuesSourceType.BYTES);
            ValuesSource.Bytes valuesSource = (ValuesSource.Bytes) config.getValuesSource();
            LeafReaderContext ctx = context.searcher().getIndexReader().leaves().get(0);
            SortedBinaryDocValues values = valuesSource.bytesValues(ctx);
            assertTrue(values.advanceExact(0));
            assertEquals(1, values.docValueCount());
            assertEquals(new BytesRef("abc"), values.nextValue());
        });
    }

    public void testEmptyKeyword() throws Exception {
        MapperService mapperService = createMapperService(fieldMapping(b -> b.field("type", "keyword")));
        withAggregationContext(mapperService, org.elasticsearch.common.collect.List.of(source(b -> {})), context -> {
            ValuesSourceConfig config;
            config = ValuesSourceConfig.resolve(context, null, "field", null, null, null, null, CoreValuesSourceType.BYTES);
            ValuesSource.Bytes valuesSource = (ValuesSource.Bytes) config.getValuesSource();
            LeafReaderContext ctx = context.searcher().getIndexReader().leaves().get(0);
            SortedBinaryDocValues values = valuesSource.bytesValues(ctx);
            assertFalse(values.advanceExact(0));
            config = ValuesSourceConfig.resolve(context, null, "field", null, "abc", null, null, CoreValuesSourceType.BYTES);
            valuesSource = (ValuesSource.Bytes) config.getValuesSource();
            values = valuesSource.bytesValues(ctx);
            assertTrue(values.advanceExact(0));
            assertEquals(1, values.docValueCount());
            assertEquals(new BytesRef("abc"), values.nextValue());
        });
    }

    public void testUnmappedKeyword() throws Exception {
        MapperService mapperService = createMapperService(mapping(b -> {}));
        withAggregationContext(mapperService, org.elasticsearch.common.collect.List.of(source(b -> {})), context -> {
            ValuesSourceConfig config;
            config = ValuesSourceConfig.resolve(context, ValueType.STRING, "field", null, null, null, null, CoreValuesSourceType.BYTES);
            ValuesSource.Bytes valuesSource = (ValuesSource.Bytes) config.getValuesSource();
            assertNotNull(valuesSource);
            assertFalse(config.hasValues());

            config = ValuesSourceConfig.resolve(context, ValueType.STRING, "field", null, "abc", null, null, CoreValuesSourceType.BYTES);
            valuesSource = (ValuesSource.Bytes) config.getValuesSource();
            LeafReaderContext ctx = context.searcher().getIndexReader().leaves().get(0);
            SortedBinaryDocValues values = valuesSource.bytesValues(ctx);
            assertTrue(values.advanceExact(0));
            assertEquals(1, values.docValueCount());
            assertEquals(new BytesRef("abc"), values.nextValue());
        });
    }

    public void testLong() throws Exception {
        MapperService mapperService = createMapperService(fieldMapping(b -> b.field("type", "long")));
        withAggregationContext(mapperService, org.elasticsearch.common.collect.List.of(source(b -> b.field("field", 42))), context -> {
            ValuesSourceConfig config;
            config = ValuesSourceConfig.resolve(context, null, "field", null, null, null, null, CoreValuesSourceType.BYTES);
            ValuesSource.Numeric valuesSource = (ValuesSource.Numeric) config.getValuesSource();
            LeafReaderContext ctx = context.searcher().getIndexReader().leaves().get(0);
            SortedNumericDocValues values = valuesSource.longValues(ctx);
            assertTrue(values.advanceExact(0));
            assertEquals(1, values.docValueCount());
            assertEquals(42, values.nextValue());
        });
    }

    public void testEmptyLong() throws Exception {
        MapperService mapperService = createMapperService(fieldMapping(b -> b.field("type", "long")));
        withAggregationContext(mapperService, org.elasticsearch.common.collect.List.of(source(b -> {})), context -> {
            ValuesSourceConfig config;
            config = ValuesSourceConfig.resolve(context, null, "field", null, null, null, null, CoreValuesSourceType.BYTES);
            ValuesSource.Numeric valuesSource = (ValuesSource.Numeric) config.getValuesSource();
            LeafReaderContext ctx = context.searcher().getIndexReader().leaves().get(0);
            SortedNumericDocValues values = valuesSource.longValues(ctx);
            assertFalse(values.advanceExact(0));

            config = ValuesSourceConfig.resolve(context, null, "field", null, 42, null, null, CoreValuesSourceType.BYTES);
            valuesSource = (ValuesSource.Numeric) config.getValuesSource();
            values = valuesSource.longValues(ctx);
            assertTrue(values.advanceExact(0));
            assertEquals(1, values.docValueCount());
            assertEquals(42, values.nextValue());
        });
    }

    public void testUnmappedLong() throws Exception {
        MapperService mapperService = createMapperService(mapping(b -> {}));
        withAggregationContext(mapperService, org.elasticsearch.common.collect.List.of(source(b -> {})), context -> {
            ValuesSourceConfig config;
            config = ValuesSourceConfig.resolve(context, ValueType.NUMBER, "field", null, null, null, null, CoreValuesSourceType.BYTES);
            ValuesSource.Numeric valuesSource = (ValuesSource.Numeric) config.getValuesSource();
            assertNotNull(valuesSource);
            assertFalse(config.hasValues());

            config = ValuesSourceConfig.resolve(context, ValueType.NUMBER, "field", null, 42, null, null, CoreValuesSourceType.BYTES);
            valuesSource = (ValuesSource.Numeric) config.getValuesSource();
            LeafReaderContext ctx = context.searcher().getIndexReader().leaves().get(0);
            SortedNumericDocValues values = valuesSource.longValues(ctx);
            assertTrue(values.advanceExact(0));
            assertEquals(1, values.docValueCount());
            assertEquals(42, values.nextValue());
        });
    }

    public void testBoolean() throws Exception {
        MapperService mapperService = createMapperService(fieldMapping(b -> b.field("type", "boolean")));
        withAggregationContext(mapperService, org.elasticsearch.common.collect.List.of(source(b -> b.field("field", true))), context -> {
            ValuesSourceConfig config;
            config = ValuesSourceConfig.resolve(context, null, "field", null, null, null, null, CoreValuesSourceType.BYTES);
            ValuesSource.Numeric valuesSource = (ValuesSource.Numeric) config.getValuesSource();
            LeafReaderContext ctx = context.searcher().getIndexReader().leaves().get(0);
            SortedNumericDocValues values = valuesSource.longValues(ctx);
            assertTrue(values.advanceExact(0));
            assertEquals(1, values.docValueCount());
            assertEquals(1, values.nextValue());
        });
    }

    public void testEmptyBoolean() throws Exception {
        MapperService mapperService = createMapperService(fieldMapping(b -> b.field("type", "boolean")));
        withAggregationContext(mapperService, org.elasticsearch.common.collect.List.of(source(b -> {})), context -> {
            ValuesSourceConfig config;
            config = ValuesSourceConfig.resolve(context, null, "field", null, null, null, null, CoreValuesSourceType.BYTES);
            ValuesSource.Numeric valuesSource = (ValuesSource.Numeric) config.getValuesSource();
            LeafReaderContext ctx = context.searcher().getIndexReader().leaves().get(0);
            SortedNumericDocValues values = valuesSource.longValues(ctx);
            assertFalse(values.advanceExact(0));

            config = ValuesSourceConfig.resolve(context, null, "field", null, true, null, null, CoreValuesSourceType.BYTES);
            valuesSource = (ValuesSource.Numeric) config.getValuesSource();
            values = valuesSource.longValues(ctx);
            assertTrue(values.advanceExact(0));
            assertEquals(1, values.docValueCount());
            assertEquals(1, values.nextValue());
        });
    }

    public void testUnmappedBoolean() throws Exception {
        MapperService mapperService = createMapperService(mapping(b -> {}));
        withAggregationContext(mapperService, org.elasticsearch.common.collect.List.of(source(b -> {})), context -> {
            ValuesSourceConfig config;
            config = ValuesSourceConfig.resolve(context, ValueType.BOOLEAN, "field", null, null, null, null, CoreValuesSourceType.BYTES);
            ValuesSource.Numeric valuesSource = (ValuesSource.Numeric) config.getValuesSource();
            assertNotNull(valuesSource);
            assertFalse(config.hasValues());

            config = ValuesSourceConfig.resolve(context, ValueType.BOOLEAN, "field", null, true, null, null, CoreValuesSourceType.BYTES);
            valuesSource = (ValuesSource.Numeric) config.getValuesSource();
            LeafReaderContext ctx = context.searcher().getIndexReader().leaves().get(0);
            SortedNumericDocValues values = valuesSource.longValues(ctx);
            assertTrue(values.advanceExact(0));
            assertEquals(1, values.docValueCount());
            assertEquals(1, values.nextValue());
        });
    }

    public void testTypeFieldDeprecation() throws IOException {
        MapperService mapperService = createMapperService(mapping(b -> {}));
        withAggregationContext(
            mapperService,
            org.elasticsearch.common.collect.List.of(source(b -> {})),
            context -> {
                ValuesSourceConfig.resolve(context, null, TypeFieldMapper.NAME, null, null, null, null, CoreValuesSourceType.BYTES);
            }
        );
        assertWarnings(TypeFieldMapper.TYPES_DEPRECATION_MESSAGE);
    }

    public void testFieldAlias() throws Exception {
        MapperService mapperService = createMapperService(mapping(b -> {
            b.startObject("field").field("type", "keyword").endObject();
            b.startObject("alias").field("type", "alias").field("path", "field").endObject();
        }));
        withAggregationContext(mapperService, org.elasticsearch.common.collect.List.of(source(b -> b.field("field", "value"))), context -> {
            ValuesSourceConfig config;
            config = ValuesSourceConfig.resolve(context, ValueType.STRING, "alias", null, null, null, null, CoreValuesSourceType.BYTES);
            ValuesSource.Bytes valuesSource = (ValuesSource.Bytes) config.getValuesSource();

            LeafReaderContext ctx = context.searcher().getIndexReader().leaves().get(0);
            SortedBinaryDocValues values = valuesSource.bytesValues(ctx);
            assertTrue(values.advanceExact(0));
            assertEquals(1, values.docValueCount());
            assertEquals(new BytesRef("value"), values.nextValue());
        });
    }
}
