/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.curator.x.async.modeled.recipes;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.NodeCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.utils.CloseableUtils;
import org.apache.curator.x.async.CompletableBaseClassForTests;
import org.apache.curator.x.async.modeled.JacksonModelSerializer;
import org.apache.curator.x.async.modeled.ModeledAsyncCuratorFramework;
import org.apache.curator.x.async.modeled.ZPath;
import org.apache.curator.x.async.modeled.models.TestModel;
import org.apache.zookeeper.data.Stat;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import java.math.BigInteger;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class TestModeledCaches extends CompletableBaseClassForTests
{
    private CuratorFramework client;
    private JacksonModelSerializer<TestModel> serializer;
    private ZPath path;
    private ModeledAsyncCuratorFramework<TestModel> modeled;

    @BeforeMethod
    @Override
    public void setup() throws Exception
    {
        super.setup();

        client = CuratorFrameworkFactory.newClient(server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1));
        client.start();

        serializer = JacksonModelSerializer.build(TestModel.class);

        path = ZPath.parse("/test/path");
        modeled = ModeledAsyncCuratorFramework.wrap(client, path, serializer);
    }

    @AfterMethod
    @Override
    public void teardown() throws Exception
    {
        CloseableUtils.closeQuietly(client);

        super.teardown();
    }

    @Test
    public void testModeledNodeCache() throws InterruptedException
    {
        try ( ModeledNodeCache<TestModel> cache = ModeledNodeCache.wrap(new NodeCache(client, path.fullPath()), serializer) )
        {
            cache.start(true);

            Semaphore changesAvailable = new Semaphore(0);
            cache.getListenable().addListener(changesAvailable::release);

            TestModel model1 = new TestModel("a", "b", "c", 1, BigInteger.TEN);
            TestModel model2 = new TestModel("d", "e", "f", 10, BigInteger.ONE);

            Stat stat = new Stat();
            modeled.create(model1, stat);
            Assert.assertTrue(timing.acquireSemaphore(changesAvailable));
            Assert.assertTrue(cache.getCurrentData().isPresent());
            Assert.assertTrue(cache.getCurrentData().get().getData().isPresent());
            Assert.assertEquals(cache.getCurrentData().get().getPath(), path);
            Assert.assertEquals(cache.getCurrentData().get().getData().get(), model1);
            Assert.assertEquals(cache.getCurrentData().get().getStat(), stat);

            timing.sleepABit();
            Assert.assertEquals(changesAvailable.availablePermits(), 0);

            modeled.update(model2);
            Assert.assertTrue(timing.acquireSemaphore(changesAvailable));
            Assert.assertTrue(cache.getCurrentData().isPresent());
            Assert.assertTrue(cache.getCurrentData().get().getData().isPresent());
            Assert.assertEquals(cache.getCurrentData().get().getPath(), path);
            Assert.assertEquals(cache.getCurrentData().get().getData().get(), model2);

            modeled.delete();
            Assert.assertTrue(timing.acquireSemaphore(changesAvailable));
            Assert.assertFalse(cache.getCurrentData().isPresent());
        }
    }

    @Test
    public void testModeledPathChildrenCacheWithData() throws InterruptedException
    {
        try ( ModeledPathChildrenCache<TestModel> cache = ModeledPathChildrenCache.wrap(new PathChildrenCache(client, path.fullPath(), true), serializer) )
        {
            cache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);

            BlockingQueue<ModeledCacheEvent<TestModel>> events = new LinkedBlockingQueue<>();
            ModeledCacheListener<TestModel> listener = events::add;
            cache.getListenable().addListener(listener);

            TestModel model1 = new TestModel("a", "b", "c", 1, BigInteger.TEN);
            TestModel model2 = new TestModel("d", "e", "f", 10, BigInteger.ONE);
            TestModel model3 = new TestModel("g", "h", "i", 100, BigInteger.ZERO);

            modeled.at("1").create(model1).thenApply(__ -> modeled.at("2").create(model2));
            ModeledCacheEvent<TestModel> event1 = events.poll(timing.milliseconds(), TimeUnit.MILLISECONDS);
            ModeledCacheEvent<TestModel> event2 = events.poll(timing.milliseconds(), TimeUnit.MILLISECONDS);
            Assert.assertNotNull(event1);
            Assert.assertNotNull(event2);
            Assert.assertEquals(event1.getType(), ModeledCacheEventType.NODE_ADDED);
            Assert.assertEquals(event1.getNode().isPresent() ? event1.getNode().get().getData().orElse(null) : null, model1);
            Assert.assertEquals(event2.getNode().isPresent() ? event2.getNode().get().getData().orElse(null) : null, model2);
            Assert.assertEquals(event1.getNode().get().getPath(), path.at("1"));
            Assert.assertEquals(event2.getNode().get().getPath(), path.at("2"));

            modeled.at("1").delete();
            event1 = events.poll(timing.milliseconds(), TimeUnit.MILLISECONDS);
            Assert.assertNotNull(event1);
            Assert.assertEquals(event1.getType(), ModeledCacheEventType.NODE_REMOVED);
            Assert.assertEquals(event1.getNode().get().getPath(), path.at("1"));

            modeled.at("2").update(model3);
            event1 = events.poll(timing.milliseconds(), TimeUnit.MILLISECONDS);
            Assert.assertNotNull(event1);
            Assert.assertEquals(event1.getType(), ModeledCacheEventType.NODE_UPDATED);
            Assert.assertEquals(event1.getNode().get().getPath(), path.at("2"));
            Assert.assertEquals(event1.getNode().isPresent() ? event1.getNode().get().getData().orElse(null) : null, model3);

            cache.getListenable().removeListener(listener);
            modeled.at("2").delete();
            Assert.assertNull(events.poll(timing.forSleepingABit().milliseconds(), TimeUnit.MILLISECONDS));  // listener is removed - shouldn't get an event
        }
    }
}
