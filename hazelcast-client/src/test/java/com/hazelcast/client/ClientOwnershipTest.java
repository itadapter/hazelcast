/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.client;

import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.impl.ClientEngineImpl;
import com.hazelcast.client.test.TestHazelcastFactory;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IExecutorService;
import com.hazelcast.test.AssertTask;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.annotation.ParallelTest;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

@RunWith(HazelcastParallelClassRunner.class)
@Category({QuickTest.class, ParallelTest.class})
public class ClientOwnershipTest extends HazelcastTestSupport {

    private final TestHazelcastFactory hazelcastFactory = new TestHazelcastFactory();

    @After
    public void cleanup() {
        hazelcastFactory.terminateAll();
    }

    @Test
    public void test_clientOwnedByMember() {
        HazelcastInstance instance = hazelcastFactory.newHazelcastInstance();
        HazelcastInstance client = hazelcastFactory.newHazelcastClient();
        String instanceUuid = instance.getLocalEndpoint().getUuid();
        String clientUuid = client.getLocalEndpoint().getUuid();

        ClientEngineImpl clientEngine = getClientEngineImpl(instance);

        assertEquals(instanceUuid, clientEngine.getOwnerUuid(clientUuid));
    }

    @Test
    public void test_clientOwnedInfoPropagatedToAllMembers() {
        HazelcastInstance instance1 = hazelcastFactory.newHazelcastInstance();
        HazelcastInstance client = hazelcastFactory.newHazelcastClient();
        HazelcastInstance instance2 = hazelcastFactory.newHazelcastInstance();

        final String instanceUuid = instance1.getLocalEndpoint().getUuid();
        final String clientUuid = client.getLocalEndpoint().getUuid();

        final ClientEngineImpl clientEngine1 = getClientEngineImpl(instance1);
        final ClientEngineImpl clientEngine2 = getClientEngineImpl(instance2);

        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                assertEquals(instanceUuid, clientEngine1.getOwnerUuid(clientUuid));
                assertEquals(instanceUuid, clientEngine2.getOwnerUuid(clientUuid));
            }
        });
    }

    @Test
    public void test_clientOwnedBySecondMember_afterFirstOwnerDies() {
        HazelcastInstance instance1 = hazelcastFactory.newHazelcastInstance();
        HazelcastInstance client = hazelcastFactory.newHazelcastClient();
        HazelcastInstance instance2 = hazelcastFactory.newHazelcastInstance();
        instance1.shutdown();

        final String instance2Uuid = instance2.getLocalEndpoint().getUuid();
        final String clientUuid = client.getLocalEndpoint().getUuid();
        final ClientEngineImpl clientEngine = getClientEngineImpl(instance2);

        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                assertEquals(instance2Uuid, clientEngine.getOwnerUuid(clientUuid));
            }
        });
    }

    @Test
    public void test_clientOwnedByAlreadyConnectedSecondMember_afterFirstOwnerDies() {
        final HazelcastInstance instance1 = hazelcastFactory.newHazelcastInstance();
        HazelcastInstance client = hazelcastFactory.newHazelcastClient();
        final HazelcastInstance instance2 = hazelcastFactory.newHazelcastInstance();

        // make sure client connected to all nodes
        IExecutorService exec = client.getExecutorService("exec");
        exec.submitToAllMembers(new DummySerializableCallable());
        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                assertEquals(1, instance1.getClientService().getConnectedClients().size());
                assertEquals(1, instance2.getClientService().getConnectedClients().size());
            }
        });

        instance1.shutdown();

        final String instance2Uuid = instance2.getLocalEndpoint().getUuid();
        final String clientUuid = client.getLocalEndpoint().getUuid();
        final ClientEngineImpl clientEngine = getClientEngineImpl(instance2);

        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                assertEquals(instance2Uuid, clientEngine.getOwnerUuid(clientUuid));
            }
        });
    }

    @Test
    public void test_ownerShipRemoved_afterClientDies() {
        HazelcastInstance instance1 = hazelcastFactory.newHazelcastInstance();
        HazelcastInstance client = hazelcastFactory.newHazelcastClient();
        HazelcastInstance instance2 = hazelcastFactory.newHazelcastInstance();

        final String instanceUuid = instance1.getLocalEndpoint().getUuid();
        final String clientUuid = client.getLocalEndpoint().getUuid();

        final ClientEngineImpl clientEngine1 = getClientEngineImpl(instance1);
        final ClientEngineImpl clientEngine2 = getClientEngineImpl(instance2);

        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                assertEquals(instanceUuid, clientEngine1.getOwnerUuid(clientUuid));
                assertEquals(instanceUuid, clientEngine2.getOwnerUuid(clientUuid));
            }
        });


        client.shutdown();

        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                assertEquals(null, clientEngine1.getOwnerUuid(clientUuid));
                assertEquals(null, clientEngine2.getOwnerUuid(clientUuid));
            }
        });
    }

    @Test
    public void test_ownerShip_afterClusterRestart() {
        HazelcastInstance instance = hazelcastFactory.newHazelcastInstance();

        ClientConfig clientConfig = new ClientConfig();
        clientConfig.getNetworkConfig().setConnectionAttemptLimit(Integer.MAX_VALUE);
        final HazelcastInstance client = hazelcastFactory.newHazelcastClient(clientConfig);
        instance.shutdown();

        final HazelcastInstance instance2 = hazelcastFactory.newHazelcastInstance();

        // wait for client to connect to node
        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                assertClusterSize(1, client);
                assertClusterSize(1, instance2);
            }
        });

        final String instanceUuid = instance2.getLocalEndpoint().getUuid();
        final ClientEngineImpl clientEngine2 = getClientEngineImpl(instance2);
        final String clientUuid = client.getLocalEndpoint().getUuid();

        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                assertEquals(instanceUuid, clientEngine2.getOwnerUuid(clientUuid));
            }
        });
    }

    @Test
    public void test_ownerShip_whenSmartClientAndOwnerDiesTogether() {
        test_ownerShip_whenClientAndOwnerDiesTogether(true);
    }

    @Test
    public void test_ownerShip_whenNonSmartClientAndOwnerDiesTogether() {
        test_ownerShip_whenClientAndOwnerDiesTogether(false);
    }

    private void test_ownerShip_whenClientAndOwnerDiesTogether(boolean smart) {
        final HazelcastInstance instance = hazelcastFactory.newHazelcastInstance();
        ClientConfig config = new ClientConfig();
        config.getNetworkConfig().setSmartRouting(smart);
        final HazelcastInstance client = hazelcastFactory.newHazelcastClient(config);
        final String clientUuid = client.getLocalEndpoint().getUuid();
        final HazelcastInstance instance2 = hazelcastFactory.newHazelcastInstance();

        client.getLifecycleService().terminate();
        instance.getLifecycleService().terminate();

        HazelcastInstance instance3 = hazelcastFactory.newHazelcastInstance();

        final ClientEngineImpl clientEngine3 = getClientEngineImpl(instance3);
        final ClientEngineImpl clientEngine2 = getClientEngineImpl(instance2);

        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                assertEquals(null, clientEngine2.getOwnerUuid(clientUuid));
                assertEquals(null, clientEngine3.getOwnerUuid(clientUuid));
            }
        });
    }
}
