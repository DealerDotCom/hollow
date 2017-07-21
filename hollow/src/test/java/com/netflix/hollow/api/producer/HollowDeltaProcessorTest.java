/*
 *
 *  Copyright 2017 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.hollow.api.producer;

import com.netflix.hollow.api.consumer.HollowConsumer;
import com.netflix.hollow.api.consumer.InMemoryBlobStore;
import com.netflix.hollow.api.objects.generic.GenericHollowObject;
import com.netflix.hollow.api.producer.HollowProducer.Populator;
import com.netflix.hollow.api.producer.HollowProducer.WriteState;
import com.netflix.hollow.api.producer.fs.HollowInMemoryBlobStager;
import com.netflix.hollow.core.index.HollowPrimaryKeyIndex;
import com.netflix.hollow.core.write.objectmapper.HollowPrimaryKey;
import com.netflix.hollow.core.write.objectmapper.HollowTypeName;
import com.netflix.hollow.core.write.objectmapper.RecordPrimaryKey;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class HollowDeltaProcessorTest {

    private InMemoryBlobStore blobStore;

    @Before
    public void setUp() {
        blobStore = new InMemoryBlobStore();
    }

    @Test
    public void continuesARestoredState() {
        HollowProducer producer = HollowProducer.withPublisher(blobStore)
                .withBlobStager(new HollowInMemoryBlobStager()).build();

        /// Initialize the data.
        long originalVersion = producer.runCycle(new Populator() {
            public void populate(WriteState state) throws Exception {
                state.add(new TypeA(1, "one", 1));
            }
        });
        
        HollowProducer newProducer = HollowProducer.withPublisher(blobStore)
                                                   .withBlobStager(new HollowInMemoryBlobStager())
                                                   .build();
        
        /// adding a new type.
        newProducer.initializeDataModel(TypeA.class, TypeB.class);
         

        HollowDeltaProcessor deltaProcessor = new HollowDeltaProcessor(newProducer);
        deltaProcessor.restore(originalVersion, blobStore);
        
        deltaProcessor.addOrModify(new TypeA(1, "one", 2));
        deltaProcessor.addOrModify(new TypeA(2, "two", 2));
        deltaProcessor.addOrModify(new TypeB(3, "three"));
        
        long version = deltaProcessor.runCycle();

        HollowConsumer consumer = HollowConsumer.withBlobRetriever(blobStore).build();
        consumer.triggerRefreshTo(originalVersion);
        consumer.triggerRefreshTo(version);

        HollowPrimaryKeyIndex idx = new HollowPrimaryKeyIndex(consumer.getStateEngine(), "TypeA", "id1", "id2");
        Assert.assertFalse(idx.containsDuplicates());

        assertTypeA(idx, 1, "one", 2L);
        assertTypeA(idx, 2, "two", 2L);
        
        /// consumers with established data models don't have visibility into new types.
        consumer = HollowConsumer.withBlobRetriever(blobStore).build();
        consumer.triggerRefreshTo(version);
        
        idx = new HollowPrimaryKeyIndex(consumer.getStateEngine(), "TypeB", "id");
        Assert.assertFalse(idx.containsDuplicates());
        
        assertTypeB(idx, 3, "three");
    }

    @Test
    public void publishAndLoadASnapshot() {
        HollowProducer producer = HollowProducer.withPublisher(blobStore)
                .withBlobStager(new HollowInMemoryBlobStager()).build();

        // / Initialize the data.
        long originalVersion = producer.runCycle(new Populator() {
            public void populate(WriteState state) throws Exception {
                state.add(new TypeA(1, "one", 1));
                state.add(new TypeA(2, "two", 2));
                state.add(new TypeA(3, "three", 3));
                state.add(new TypeA(4, "four", 4));
                state.add(new TypeA(5, "five", 5));

                state.add(new TypeB(1, "1"));
                state.add(new TypeB(2, "2"));
                state.add(new TypeB(3, "3"));
                state.add(new TypeB(4, "4"));
            }
        });

        /*
         * HollowProducer newProducer = HollowProducer.withPublisher(blobStore)
         * .withBlobStager(new HollowInMemoryBlobStager()) .build();
         * 
         * newProducer.initializeDataModel(TypeA.class, TypeB.class);
         */

        HollowDeltaProcessor deltaProcessor = new HollowDeltaProcessor(producer);
        // deltaProcessor.restore(originalVersion, blobStore);

        deltaProcessor.addOrModify(new TypeA(1, "one", 100));
        deltaProcessor.addOrModify(new TypeA(2, "two", 2));
        deltaProcessor.addOrModify(new TypeA(3, "three", 300));
        deltaProcessor.addOrModify(new TypeA(3, "three", 3));
        deltaProcessor.addOrModify(new TypeA(4, "five", 6));
        deltaProcessor.delete(new TypeA(5, "five", 5));

        deltaProcessor.delete(new TypeB(2, "3"));
        deltaProcessor.addOrModify(new TypeB(5, "5"));
        deltaProcessor.addOrModify(new TypeB(5, "6"));
        deltaProcessor.delete(new RecordPrimaryKey("TypeB", new Object[] { 3 }));

        long nextVersion = deltaProcessor.runCycle();

        deltaProcessor.addOrModify(new TypeA(1, "one", 1000));

        long finalVersion = deltaProcessor.runCycle();

        HollowConsumer consumer = HollowConsumer.withBlobRetriever(blobStore).build();
        consumer.triggerRefreshTo(originalVersion);
        consumer.triggerRefreshTo(nextVersion);

        HollowPrimaryKeyIndex idx = new HollowPrimaryKeyIndex(consumer.getStateEngine(), "TypeA", "id1", "id2");
        Assert.assertFalse(idx.containsDuplicates());

        assertTypeA(idx, 1, "one", 100L);
        assertTypeA(idx, 2, "two", 2L);
        assertTypeA(idx, 3, "three", 3L);
        assertTypeA(idx, 4, "four", 4L);
        assertTypeA(idx, 4, "five", 6L);
        assertTypeA(idx, 5, "five", null);

        idx = new HollowPrimaryKeyIndex(consumer.getStateEngine(), "TypeB",
                "id");
        Assert.assertFalse(idx.containsDuplicates());

        assertTypeB(idx, 1, "1");
        assertTypeB(idx, 2, null);
        assertTypeB(idx, 3, null);
        assertTypeB(idx, 4, "4");
        assertTypeB(idx, 5, "6");

        consumer.triggerRefreshTo(finalVersion);

        idx = new HollowPrimaryKeyIndex(consumer.getStateEngine(), "TypeA",
                "id1", "id2");
        Assert.assertFalse(idx.containsDuplicates());

        assertTypeA(idx, 1, "one", 1000L);
        assertTypeA(idx, 2, "two", 2L);
        assertTypeA(idx, 3, "three", 3L);
        assertTypeA(idx, 4, "four", 4L);
        assertTypeA(idx, 4, "five", 6L);
        assertTypeA(idx, 5, "five", null);
    }

    private void assertTypeA(HollowPrimaryKeyIndex typeAIdx, int id1,
            String id2, Long expectedValue) {
        int ordinal = typeAIdx.getMatchingOrdinal(id1, id2);

        if (expectedValue == null) {
            Assert.assertEquals(-1, ordinal);
        } else {
            Assert.assertNotEquals(-1, ordinal);
            GenericHollowObject obj = new GenericHollowObject(
                    typeAIdx.getTypeState(), ordinal);
            Assert.assertEquals(expectedValue.longValue(), obj.getLong("value"));
        }
    }

    private void assertTypeB(HollowPrimaryKeyIndex typeBIdx, int id1,
            String expectedValue) {
        int ordinal = typeBIdx.getMatchingOrdinal(id1);

        if (expectedValue == null) {
            Assert.assertEquals(-1, ordinal);
        } else {
            Assert.assertNotEquals(-1, ordinal);
            GenericHollowObject obj = new GenericHollowObject(
                    typeBIdx.getTypeState(), ordinal);
            Assert.assertEquals(expectedValue, obj.getObject("value")
                    .getString("value"));
        }
    }

    @SuppressWarnings("unused")
    @HollowPrimaryKey(fields = { "id1", "id2" })
    private static class TypeA {
        int id1;
        String id2;
        long value;

        public TypeA(int id1, String id2, long value) {
            this.id1 = id1;
            this.id2 = id2;
            this.value = value;
        }
    }

    @SuppressWarnings("unused")
    @HollowPrimaryKey(fields = "id")
    private static class TypeB {
        int id;
        @HollowTypeName(name = "TypeBValue")
        String value;

        public TypeB(int id, String value) {
            this.id = id;
            this.value = value;
        }
    }
}