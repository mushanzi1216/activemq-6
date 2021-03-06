/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.tests.integration.cluster.bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.activemq.api.core.ActiveMQBuffer;
import org.apache.activemq.api.core.ActiveMQException;
import org.apache.activemq.api.core.Interceptor;
import org.apache.activemq.api.core.SimpleString;
import org.apache.activemq.api.core.TransportConfiguration;
import org.apache.activemq.api.core.client.ClientConsumer;
import org.apache.activemq.api.core.client.ClientMessage;
import org.apache.activemq.api.core.client.ClientProducer;
import org.apache.activemq.api.core.client.ClientSession;
import org.apache.activemq.api.core.client.ClientSessionFactory;
import org.apache.activemq.api.core.client.ActiveMQClient;
import org.apache.activemq.api.core.client.ServerLocator;
import org.apache.activemq.core.config.BridgeConfiguration;
import org.apache.activemq.core.config.CoreQueueConfiguration;
import org.apache.activemq.core.postoffice.DuplicateIDCache;
import org.apache.activemq.core.postoffice.impl.PostOfficeImpl;
import org.apache.activemq.core.protocol.core.Packet;
import org.apache.activemq.core.protocol.core.impl.wireformat.SessionSendContinuationMessage;
import org.apache.activemq.core.protocol.core.impl.wireformat.SessionSendLargeMessage;
import org.apache.activemq.core.protocol.core.impl.wireformat.SessionSendMessage;
import org.apache.activemq.core.remoting.impl.invm.TransportConstants;
import org.apache.activemq.core.server.ActiveMQServer;
import org.apache.activemq.core.server.MessageReference;
import org.apache.activemq.core.server.Queue;
import org.apache.activemq.core.server.cluster.impl.BridgeImpl;
import org.apache.activemq.core.transaction.impl.TransactionImpl;
import org.apache.activemq.spi.core.protocol.RemotingConnection;
import org.apache.activemq.tests.integration.IntegrationTestLogger;
import org.apache.activemq.tests.util.RandomUtil;
import org.apache.activemq.tests.util.ServiceTestBase;
import org.apache.activemq.tests.util.UnitTestCase;
import org.apache.activemq.utils.LinkedListIterator;
import org.apache.activemq.utils.ReusableLatch;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(value = Parameterized.class)
public class BridgeTest extends ServiceTestBase
{

   private ActiveMQServer server0;
   private ActiveMQServer server1;
   private ServerLocator locator;

   private final boolean netty;


   @Parameterized.Parameters(name = "isNetty={0}")
   public static Collection getParameters()
   {
      return Arrays.asList(new Object[][]{
         {true},
         {false}
      });
   }

   public BridgeTest(boolean isNetty)
   {
      this.netty = isNetty;
   }

   protected boolean isNetty()
   {
      return netty;
   }


   @Override
   @Before
   public void setUp() throws Exception
   {
      StopInterceptor.reset();
      super.setUp();
   }


   private String getConnector()
   {
      if (isNetty())
      {
         return NETTY_CONNECTOR_FACTORY;
      }
      else
      {
         return INVM_CONNECTOR_FACTORY;
      }
   }

   @Test
   public void testSimpleBridge() throws Exception
   {
      internaltestSimpleBridge(false, false);
   }

   @Test
   public void testSimpleBridgeFiles() throws Exception
   {
      internaltestSimpleBridge(false, true);
   }

   @Test
   public void testSimpleBridgeLargeMessageNullPersistence() throws Exception
   {
      internaltestSimpleBridge(true, false);
   }

   @Test
   public void testSimpleBridgeLargeMessageFiles() throws Exception
   {
      internaltestSimpleBridge(true, true);
   }

   public void internaltestSimpleBridge(final boolean largeMessage, final boolean useFiles) throws Exception
   {
      Map<String, Object> server0Params = new HashMap<String, Object>();
      server0 = createClusteredServerWithParams(isNetty(), 0, useFiles, server0Params);

      Map<String, Object> server1Params = new HashMap<String, Object>();
      addTargetParameters(server1Params);
      server1 = createClusteredServerWithParams(isNetty(), 1, useFiles, server1Params);

      final String testAddress = "testAddress";
      final String queueName0 = "queue0";
      final String forwardAddress = "forwardAddress";
      final String queueName1 = "queue1";

      // Map<String, TransportConfiguration> connectors = new HashMap<String, TransportConfiguration>();
      TransportConfiguration server0tc = new TransportConfiguration(getConnector(), server0Params);

      TransportConfiguration server1tc = new TransportConfiguration(getConnector(), server1Params);

      HashMap<String, TransportConfiguration> connectors = new HashMap<String, TransportConfiguration>();
      connectors.put(server1tc.getName(), server1tc);
      server0.getConfiguration().setConnectorConfigurations(connectors);

      final int messageSize = 1024;

      final int numMessages = 10;

      ArrayList<String> connectorConfig = new ArrayList<String>();
      connectorConfig.add(server1tc.getName());
      BridgeConfiguration bridgeConfiguration = new BridgeConfiguration()
         .setName("bridge1")
         .setQueueName(queueName0)
         .setForwardingAddress(forwardAddress)
         .setRetryInterval(1000)
         .setReconnectAttemptsOnSameNode(-1)
         .setUseDuplicateDetection(false)
         .setConfirmationWindowSize(numMessages * messageSize / 2)
         .setStaticConnectors(connectorConfig);

      List<BridgeConfiguration> bridgeConfigs = new ArrayList<BridgeConfiguration>();
      bridgeConfigs.add(bridgeConfiguration);
      server0.getConfiguration().setBridgeConfigurations(bridgeConfigs);

      CoreQueueConfiguration queueConfig0 = new CoreQueueConfiguration()
         .setAddress(testAddress)
         .setName(queueName0);
      List<CoreQueueConfiguration> queueConfigs0 = new ArrayList<CoreQueueConfiguration>();
      queueConfigs0.add(queueConfig0);
      server0.getConfiguration().setQueueConfigurations(queueConfigs0);

      CoreQueueConfiguration queueConfig1 = new CoreQueueConfiguration()
         .setAddress(forwardAddress)
         .setName(queueName1);
      List<CoreQueueConfiguration> queueConfigs1 = new ArrayList<CoreQueueConfiguration>();
      queueConfigs1.add(queueConfig1);
      server1.getConfiguration().setQueueConfigurations(queueConfigs1);

      server1.start();
      server0.start();
      locator = addServerLocator(ActiveMQClient.createServerLocatorWithoutHA(server0tc, server1tc));
      ClientSessionFactory sf0 = addSessionFactory(locator.createSessionFactory(server0tc));

      ClientSessionFactory sf1 = addSessionFactory(locator.createSessionFactory(server1tc));

      ClientSession session0 = sf0.createSession(false, true, true);

      ClientSession session1 = sf1.createSession(false, true, true);

      ClientProducer producer0 = session0.createProducer(new SimpleString(testAddress));

      ClientConsumer consumer1 = session1.createConsumer(queueName1);

      session1.start();

      final byte[] bytes = new byte[messageSize];

      final SimpleString propKey = new SimpleString("testkey");

      for (int i = 0; i < numMessages; i++)
      {
         ClientMessage message = session0.createMessage(true);

         if (largeMessage)
         {
            message.setBodyInputStream(UnitTestCase.createFakeLargeStream(1024 * 1024));
         }

         message.putIntProperty(propKey, i);

         message.getBodyBuffer().writeBytes(bytes);

         producer0.send(message);
      }

      for (int i = 0; i < numMessages; i++)
      {
         ClientMessage message = consumer1.receive(5000);

         Assert.assertNotNull(message);

         Assert.assertEquals(i, message.getObjectProperty(propKey));

         if (largeMessage)
         {
            readMessages(message);
         }

         message.acknowledge();
      }

      Assert.assertNull(consumer1.receiveImmediate());

      session0.close();

      session1.close();

      sf0.close();

      sf1.close();

      closeFields();
      assertEquals(0, loadQueues(server0).size());

   }


   @Test
   public void testLostMessageSimpleMessage() throws Exception
   {
      internalTestMessageLoss(false);
   }

   @Test
   public void testLostMessageLargeMessage() throws Exception
   {
      internalTestMessageLoss(true);
   }

   /**
    * This test will ignore messages
    * What will cause the bridge to fail with a timeout
    * The bridge should still recover the failure and reconnect on that case
    */
   public void internalTestMessageLoss(final boolean largeMessage) throws Exception
   {
      class MyInterceptor implements Interceptor
      {
         public boolean ignoreSends = true;
         public CountDownLatch latch;

         MyInterceptor(int numberOfIgnores)
         {
            latch = new CountDownLatch(numberOfIgnores);
         }

         public boolean intercept(Packet packet, RemotingConnection connection) throws ActiveMQException
         {
            if (ignoreSends && packet instanceof SessionSendMessage ||
               ignoreSends && packet instanceof SessionSendLargeMessage ||
               ignoreSends && packet instanceof SessionSendContinuationMessage && !((SessionSendContinuationMessage) packet).isContinues())
            {
               IntegrationTestLogger.LOGGER.info("IGNORED: " + packet);
               latch.countDown();
               return false;
            }
            else
            {
               IntegrationTestLogger.LOGGER.info(packet);
               return true;
            }
         }

      }

      MyInterceptor myInterceptor = new MyInterceptor(3);

      Map<String, Object> server0Params = new HashMap<String, Object>();
      server0 = createClusteredServerWithParams(isNetty(), 0, true, server0Params);

      Map<String, Object> server1Params = new HashMap<String, Object>();
      addTargetParameters(server1Params);
      server1 = createClusteredServerWithParams(isNetty(), 1, true, server1Params);

      final String testAddress = "testAddress";
      final String queueName0 = "queue0";
      final String forwardAddress = "forwardAddress";
      final String queueName1 = "queue1";

      TransportConfiguration server0tc = new TransportConfiguration(getConnector(), server0Params);
      TransportConfiguration server1tc = new TransportConfiguration(getConnector(), server1Params);

      HashMap<String, TransportConfiguration> connectors = new HashMap<String, TransportConfiguration>();
      connectors.put(server1tc.getName(), server1tc);
      server0.getConfiguration().setConnectorConfigurations(connectors);

      final int messageSize = 1024;

      final int numMessages = 1;

      ArrayList<String> connectorConfig = new ArrayList<String>();
      connectorConfig.add(server1tc.getName());
      BridgeConfiguration bridgeConfiguration = new BridgeConfiguration()
         .setName("bridge1")
         .setQueueName(queueName0)
         .setForwardingAddress(forwardAddress)
         .setRetryInterval(1000)
         .setReconnectAttempts(-1)
         .setReconnectAttemptsOnSameNode(-1)
         .setUseDuplicateDetection(false)
         .setConfirmationWindowSize(numMessages * messageSize / 2)
         .setStaticConnectors(connectorConfig)
         .setCallTimeout(5000);

      List<BridgeConfiguration> bridgeConfigs = new ArrayList<BridgeConfiguration>();
      bridgeConfigs.add(bridgeConfiguration);
      server0.getConfiguration().setBridgeConfigurations(bridgeConfigs);

      CoreQueueConfiguration queueConfig0 = new CoreQueueConfiguration()
         .setAddress(testAddress)
         .setName(queueName0);
      List<CoreQueueConfiguration> queueConfigs0 = new ArrayList<CoreQueueConfiguration>();
      queueConfigs0.add(queueConfig0);
      server0.getConfiguration().setQueueConfigurations(queueConfigs0);

      CoreQueueConfiguration queueConfig1 = new CoreQueueConfiguration()
         .setAddress(forwardAddress)
         .setName(queueName1);
      List<CoreQueueConfiguration> queueConfigs1 = new ArrayList<CoreQueueConfiguration>();
      queueConfigs1.add(queueConfig1);
      server1.getConfiguration().setQueueConfigurations(queueConfigs1);

      server1.start();

      server1.getRemotingService().addIncomingInterceptor(myInterceptor);

      server0.start();
      locator = addServerLocator(ActiveMQClient.createServerLocatorWithoutHA(server0tc, server1tc));
      ClientSessionFactory sf0 = addSessionFactory(locator.createSessionFactory(server0tc));

      ClientSessionFactory sf1 = addSessionFactory(locator.createSessionFactory(server1tc));

      ClientSession session0 = sf0.createSession(false, true, true);

      ClientSession session1 = sf1.createSession(false, true, true);

      ClientProducer producer0 = session0.createProducer(new SimpleString(testAddress));

      ClientConsumer consumer1 = session1.createConsumer(queueName1);

      session1.start();

      final byte[] bytes = new byte[messageSize];

      final SimpleString propKey = new SimpleString("testkey");

      for (int i = 0; i < numMessages; i++)
      {
         ClientMessage message = session0.createMessage(true);

         if (largeMessage)
         {
            message.setBodyInputStream(UnitTestCase.createFakeLargeStream(1024 * 1024));
         }

         message.putIntProperty(propKey, i);

         message.getBodyBuffer().writeBytes(bytes);

         producer0.send(message);
      }

      assertTrue("where is the countDown?", myInterceptor.latch.await(30, TimeUnit.SECONDS));
      myInterceptor.ignoreSends = false;
      server1.getRemotingService().removeIncomingInterceptor(myInterceptor);
      IntegrationTestLogger.LOGGER.info("No longer ignoring packets.");

      for (int i = 0; i < numMessages; i++)
      {
         ClientMessage message = consumer1.receive(30000);

         Assert.assertNotNull(message);

         Assert.assertEquals(i, message.getObjectProperty(propKey));

         if (largeMessage)
         {
            readMessages(message);
         }

         message.acknowledge();
      }

      Assert.assertNull(consumer1.receiveImmediate());

      session0.close();

      session1.close();

      sf0.close();

      sf1.close();
      closeFields();
      assertEquals("there should be no queues", 0, loadQueues(server0).size());
   }

   /**
    * @param server1Params
    */
   private void addTargetParameters(final Map<String, Object> server1Params)
   {
      if (isNetty())
      {
         server1Params.put("port", org.apache.activemq.core.remoting.impl.netty.TransportConstants.DEFAULT_PORT + 1);
      }
      else
      {
         server1Params.put(TransportConstants.SERVER_ID_PROP_NAME, 1);
      }
   }

   /**
    * @param message
    */
   private void readMessages(final ClientMessage message)
   {
      byte[] byteRead = new byte[1024];

      for (int j = 0; j < 1024; j++)
      {
         message.getBodyBuffer().readBytes(byteRead);
      }
   }

   @Test
   public void testWithFilter() throws Exception
   {
      internalTestWithFilter(false, false);
   }

   @Test
   public void testWithFilterFiles() throws Exception
   {
      internalTestWithFilter(false, true);
   }

   @Test
   public void testWithFilterLargeMessages() throws Exception
   {
      internalTestWithFilter(true, false);
   }

   @Test
   public void testWithFilterLargeMessagesFiles() throws Exception
   {
      internalTestWithFilter(true, true);
   }

   public void internalTestWithFilter(final boolean largeMessage, final boolean useFiles) throws Exception
   {

      final int numMessages = 10;

      Map<String, Object> server0Params = new HashMap<String, Object>();
      server0 = createClusteredServerWithParams(isNetty(), 0, useFiles, server0Params);

      Map<String, Object> server1Params = new HashMap<String, Object>();
      addTargetParameters(server1Params);
      server1 = createClusteredServerWithParams(isNetty(), 1, useFiles, server1Params);

      final String testAddress = "testAddress";
      final String queueName0 = "queue0";
      final String forwardAddress = "forwardAddress";
      final String queueName1 = "queue1";

      Map<String, TransportConfiguration> connectors = new HashMap<String, TransportConfiguration>();
      TransportConfiguration server0tc = new TransportConfiguration(getConnector(), server0Params);
      TransportConfiguration server1tc = new TransportConfiguration(getConnector(), server1Params);
      connectors.put(server1tc.getName(), server1tc);

      server0.getConfiguration().setConnectorConfigurations(connectors);

      final String filterString = "animal='goat'";

      ArrayList<String> staticConnectors = new ArrayList<String>();
      staticConnectors.add(server1tc.getName());
      BridgeConfiguration bridgeConfiguration = new BridgeConfiguration()
         .setName("bridge1")
         .setQueueName(queueName0)
         .setForwardingAddress(forwardAddress)
         .setFilterString(filterString)
         .setRetryInterval(1000)
         .setReconnectAttemptsOnSameNode(-1)
         .setUseDuplicateDetection(false)
         .setConfirmationWindowSize(0)
         .setStaticConnectors(staticConnectors);

      List<BridgeConfiguration> bridgeConfigs = new ArrayList<BridgeConfiguration>();
      bridgeConfigs.add(bridgeConfiguration);
      server0.getConfiguration().setBridgeConfigurations(bridgeConfigs);

      CoreQueueConfiguration queueConfig0 = new CoreQueueConfiguration()
         .setAddress(testAddress)
         .setName(queueName0);
      List<CoreQueueConfiguration> queueConfigs0 = new ArrayList<CoreQueueConfiguration>();
      queueConfigs0.add(queueConfig0);
      server0.getConfiguration().setQueueConfigurations(queueConfigs0);

      CoreQueueConfiguration queueConfig1 = new CoreQueueConfiguration()
         .setAddress(forwardAddress)
         .setName(queueName1);
      List<CoreQueueConfiguration> queueConfigs1 = new ArrayList<CoreQueueConfiguration>();
      queueConfigs1.add(queueConfig1);
      server1.getConfiguration().setQueueConfigurations(queueConfigs1);

      server1.start();
      server0.start();

      locator = addServerLocator(ActiveMQClient.createServerLocatorWithoutHA(server0tc, server1tc));
      ClientSessionFactory sf0 = locator.createSessionFactory(server0tc);

      ClientSessionFactory sf1 = locator.createSessionFactory(server1tc);

      ClientSession session0 = sf0.createSession(false, true, true);

      ClientSession session1 = sf1.createSession(false, true, true);

      ClientProducer producer0 = session0.createProducer(new SimpleString(testAddress));

      ClientConsumer consumer1 = session1.createConsumer(queueName1);

      session1.start();

      final SimpleString propKey = new SimpleString("testkey");

      final SimpleString selectorKey = new SimpleString("animal");

      for (int i = 0; i < numMessages; i++)
      {
         ClientMessage message = session0.createMessage(true);

         message.putIntProperty(propKey, i);

         message.putStringProperty(selectorKey, new SimpleString("monkey"));

         if (largeMessage)
         {
            message.setBodyInputStream(UnitTestCase.createFakeLargeStream(1024 * 1024));
         }

         producer0.send(message);
      }

      Assert.assertNull(consumer1.receiveImmediate());

      for (int i = 0; i < numMessages; i++)
      {
         ClientMessage message = session0.createMessage(true);

         message.putIntProperty(propKey, i);

         message.putStringProperty(selectorKey, new SimpleString("goat"));

         if (largeMessage)
         {
            message.setBodyInputStream(UnitTestCase.createFakeLargeStream(1024 * 1024));
         }

         producer0.send(message);
      }

      for (int i = 0; i < numMessages; i++)
      {
         ClientMessage message = consumer1.receive(4000);

         Assert.assertNotNull(message);

         Assert.assertEquals("goat", message.getStringProperty(selectorKey));

         Assert.assertEquals(i, message.getObjectProperty(propKey));

         message.acknowledge();

         if (largeMessage)
         {
            readMessages(message);
         }
      }

      session0.commit();

      session1.commit();

      Assert.assertNull(consumer1.receiveImmediate());

      session0.close();

      session1.close();

      sf0.close();

      sf1.close();
      closeFields();
      if (useFiles)
      {
         Map<Long, AtomicInteger> counters = loadQueues(server0);
         assertEquals(1, counters.size());
         Long key = counters.keySet().iterator().next();

         AtomicInteger value = counters.get(key);
         assertNotNull(value);
         assertEquals(numMessages, counters.get(key).intValue());
      }


   }

   // Created to verify JBPAPP-6057
   @Test
   public void testStartLater() throws Exception
   {
      Map<String, Object> server0Params = new HashMap<String, Object>();
      server0 = createClusteredServerWithParams(isNetty(), 0, true, server0Params);

      Map<String, Object> server1Params = new HashMap<String, Object>();
      addTargetParameters(server1Params);
      server1 = createClusteredServerWithParams(isNetty(), 1, true, server1Params);

      final String testAddress = "testAddress";
      final String queueName0 = "queue0";
      final String forwardAddress = "jms.queue.forwardAddress";
      final String queueName1 = "forwardAddress";

      Map<String, TransportConfiguration> connectors = new HashMap<String, TransportConfiguration>();
      TransportConfiguration server0tc = new TransportConfiguration(getConnector(), server0Params);
      TransportConfiguration server1tc = new TransportConfiguration(getConnector(), server1Params);
      connectors.put(server1tc.getName(), server1tc);

      server0.getConfiguration().setConnectorConfigurations(connectors);

      ArrayList<String> staticConnectors = new ArrayList<String>();
      staticConnectors.add(server1tc.getName());
      BridgeConfiguration bridgeConfiguration = new BridgeConfiguration()
         .setName("bridge1")
         .setQueueName(queueName0)
         .setForwardingAddress(forwardAddress)
         .setRetryInterval(100)
         .setReconnectAttemptsOnSameNode(-1)
         .setUseDuplicateDetection(false)
         .setConfirmationWindowSize(1024)
         .setStaticConnectors(staticConnectors);

      List<BridgeConfiguration> bridgeConfigs = new ArrayList<BridgeConfiguration>();
      bridgeConfigs.add(bridgeConfiguration);
      server0.getConfiguration().setBridgeConfigurations(bridgeConfigs);

      CoreQueueConfiguration queueConfig0 = new CoreQueueConfiguration()
         .setAddress(testAddress)
         .setName(queueName0);
      List<CoreQueueConfiguration> queueConfigs0 = new ArrayList<CoreQueueConfiguration>();
      queueConfigs0.add(queueConfig0);
      server0.getConfiguration().setQueueConfigurations(queueConfigs0);

      server0.start();

      locator = addServerLocator(ActiveMQClient.createServerLocatorWithoutHA(server0tc, server1tc));
      ClientSessionFactory sf0 = locator.createSessionFactory(server0tc);

      ClientSession session0 = sf0.createSession(false, true, true);

      ClientProducer producer0 = session0.createProducer(new SimpleString(testAddress));

      final int numMessages = 100;

      final SimpleString propKey = new SimpleString("testkey");

      final SimpleString selectorKey = new SimpleString("animal");

      for (int i = 0; i < numMessages; i++)
      {
         ClientMessage message = session0.createMessage(true);

         message.getBodyBuffer().writeBytes(new byte[1024]);

         message.putIntProperty(propKey, i);

         message.putStringProperty(selectorKey, new SimpleString("monkey" + i));

         producer0.send(message);
      }

      server1.start();

      Thread.sleep(1000);

      ClientSessionFactory sf1 = locator.createSessionFactory(server1tc);

      ClientSession session1 = sf1.createSession(false, true, true);

      try
      {
         session1.createQueue(forwardAddress, queueName1);
      }
      catch (Throwable ignored)
      {
         ignored.printStackTrace();
      }

      ClientConsumer consumer1 = session1.createConsumer(queueName1);

      session1.start();

      for (int i = 0; i < numMessages; i++)
      {
         ClientMessage message = consumer1.receive(5000);
         assertNotNull(message);
         message.acknowledge();
      }

      session1.commit();

      Assert.assertNull(consumer1.receiveImmediate());

      consumer1.close();

      session1.deleteQueue(queueName1);

      session1.close();

      sf1.close();

      server1.stop();

      session0.close();

      sf0.close();
      closeFields();
      assertEquals(0, loadQueues(server0).size());


   }

   @Test
   public void testWithDuplicates() throws Exception
   {
      Map<String, Object> server0Params = new HashMap<String, Object>();
      server0 = createClusteredServerWithParams(isNetty(), 0, true, server0Params);

      Map<String, Object> server1Params = new HashMap<String, Object>();
      addTargetParameters(server1Params);
      server1 = createClusteredServerWithParams(isNetty(), 1, true, server1Params);

      final String testAddress = "testAddress";
      final String queueName0 = "queue0";
      final String forwardAddress = "jms.queue.forwardAddress";
      final String queueName1 = "forwardAddress";

      Map<String, TransportConfiguration> connectors = new HashMap<String, TransportConfiguration>();
      TransportConfiguration server0tc = new TransportConfiguration(getConnector(), server0Params);
      TransportConfiguration server1tc = new TransportConfiguration(getConnector(), server1Params);
      connectors.put(server1tc.getName(), server1tc);

      server0.getConfiguration().setConnectorConfigurations(connectors);

      ArrayList<String> staticConnectors = new ArrayList<String>();
      staticConnectors.add(server1tc.getName());
      BridgeConfiguration bridgeConfiguration = new BridgeConfiguration()
         .setName("bridge1")
         .setQueueName(queueName0)
         .setForwardingAddress(forwardAddress)
         .setRetryInterval(100)
         .setReconnectAttemptsOnSameNode(-1)
         .setConfirmationWindowSize(0)
         .setStaticConnectors(staticConnectors);

      List<BridgeConfiguration> bridgeConfigs = new ArrayList<BridgeConfiguration>();
      bridgeConfigs.add(bridgeConfiguration);
      server0.getConfiguration().setBridgeConfigurations(bridgeConfigs);

      CoreQueueConfiguration queueConfig0 = new CoreQueueConfiguration()
         .setAddress(testAddress)
         .setName(queueName0);
      List<CoreQueueConfiguration> queueConfigs0 = new ArrayList<CoreQueueConfiguration>();
      queueConfigs0.add(queueConfig0);
      server0.getConfiguration().setQueueConfigurations(queueConfigs0);

      server0.start();

      locator = addServerLocator(ActiveMQClient.createServerLocatorWithoutHA(server0tc, server1tc));
      ClientSessionFactory sf0 = locator.createSessionFactory(server0tc);

      ClientSession session0 = sf0.createSession(false, true, true);

      ClientProducer producer0 = session0.createProducer(new SimpleString(testAddress));

      final int numMessages = 1000;

      final SimpleString propKey = new SimpleString("testkey");

      final SimpleString selectorKey = new SimpleString("animal");

      for (int i = 0; i < numMessages; i++)
      {
         ClientMessage message = session0.createMessage(true);

         message.getBodyBuffer().writeBytes(new byte[1024]);

         message.putIntProperty(propKey, i);

         message.putStringProperty(selectorKey, new SimpleString("monkey" + i));

         producer0.send(message);
      }

      server1.start();

      // Inserting the duplicateIDs so the bridge will fail in a few
      {
         long[] ids = new long[100];

         Queue queue = server0.locateQueue(new SimpleString(queueName0));
         LinkedListIterator<MessageReference> iterator = queue.iterator();

         for (int i = 0; i < 100; i++)
         {
            iterator.hasNext();
            ids[i] = iterator.next().getMessage().getMessageID();
         }

         iterator.close();

         DuplicateIDCache duplicateTargetCache = server1.getPostOffice()
            .getDuplicateIDCache(PostOfficeImpl.BRIDGE_CACHE_STR.concat(forwardAddress));

         TransactionImpl tx = new TransactionImpl(server1.getStorageManager());
         for (long id : ids)
         {
            byte[] duplicateArray = BridgeImpl.getDuplicateBytes(server0.getNodeManager().getUUID(), id);
            duplicateTargetCache.addToCache(duplicateArray, tx);
         }
         tx.commit();
      }

      Thread.sleep(1000);

      ClientSessionFactory sf1 = locator.createSessionFactory(server1tc);

      ClientSession session1 = sf1.createSession(false, true, true);

      try
      {
         session1.createQueue(forwardAddress, queueName1);
      }
      catch (Throwable ignored)
      {
         ignored.printStackTrace();
      }

      ClientConsumer consumer1 = session1.createConsumer(queueName1);

      session1.start();

      for (int i = 100; i < numMessages; i++)
      {
         ClientMessage message = consumer1.receive(5000);
         assertNotNull(message);
         assertEquals(i, message.getIntProperty(propKey).intValue());
         message.acknowledge();
      }

      session1.commit();

      Assert.assertNull(consumer1.receiveImmediate());

      consumer1.close();

      session1.deleteQueue(queueName1);

      session1.close();

      sf1.close();

      server1.stop();

      session0.close();

      sf0.close();

      closeFields();
      assertEquals(0, loadQueues(server0).size());


   }

   private void closeFields() throws Exception
   {
      locator.close();
      server0.stop();
      server1.stop();
   }

   @Test
   public void testWithTransformer() throws Exception
   {
      internaltestWithTransformer(false);
   }

   @Test
   public void testWithTransformerFiles() throws Exception
   {
      internaltestWithTransformer(true);
   }

   public void internaltestWithTransformer(final boolean useFiles) throws Exception
   {
      Map<String, Object> server0Params = new HashMap<String, Object>();
      server0 = createClusteredServerWithParams(isNetty(), 0, false, server0Params);

      Map<String, Object> server1Params = new HashMap<String, Object>();
      addTargetParameters(server1Params);
      server1 = createClusteredServerWithParams(isNetty(), 1, false, server1Params);

      final String testAddress = "testAddress";
      final String queueName0 = "queue0";
      final String forwardAddress = "forwardAddress";
      final String queueName1 = "queue1";

      Map<String, TransportConfiguration> connectors = new HashMap<String, TransportConfiguration>();
      TransportConfiguration server0tc = new TransportConfiguration(getConnector(), server0Params);
      TransportConfiguration server1tc = new TransportConfiguration(getConnector(), server1Params);
      connectors.put(server1tc.getName(), server1tc);

      server0.getConfiguration().setConnectorConfigurations(connectors);

      ArrayList<String> staticConnectors = new ArrayList<String>();
      staticConnectors.add(server1tc.getName());

      BridgeConfiguration bridgeConfiguration = new BridgeConfiguration()
         .setName("bridge1")
         .setQueueName(queueName0)
         .setForwardingAddress(forwardAddress)
         .setTransformerClassName(SimpleTransformer.class.getName())
         .setRetryInterval(1000)
         .setReconnectAttemptsOnSameNode(-1)
         .setUseDuplicateDetection(false)
         .setConfirmationWindowSize(1024)
         .setStaticConnectors(staticConnectors);


      List<BridgeConfiguration> bridgeConfigs = new ArrayList<BridgeConfiguration>();
      bridgeConfigs.add(bridgeConfiguration);
      server0.getConfiguration().setBridgeConfigurations(bridgeConfigs);

      CoreQueueConfiguration queueConfig0 = new CoreQueueConfiguration()
         .setAddress(testAddress)
         .setName(queueName0);
      List<CoreQueueConfiguration> queueConfigs0 = new ArrayList<CoreQueueConfiguration>();
      queueConfigs0.add(queueConfig0);
      server0.getConfiguration().setQueueConfigurations(queueConfigs0);

      CoreQueueConfiguration queueConfig1 = new CoreQueueConfiguration()
         .setAddress(forwardAddress)
         .setName(queueName1);
      List<CoreQueueConfiguration> queueConfigs1 = new ArrayList<CoreQueueConfiguration>();
      queueConfigs1.add(queueConfig1);
      server1.getConfiguration().setQueueConfigurations(queueConfigs1);

      server1.start();
      server0.start();

      locator = addServerLocator(ActiveMQClient.createServerLocatorWithoutHA(server0tc, server1tc));
      ClientSessionFactory sf0 = locator.createSessionFactory(server0tc);

      ClientSessionFactory sf1 = locator.createSessionFactory(server1tc);

      ClientSession session0 = sf0.createSession(false, true, true);

      ClientSession session1 = sf1.createSession(false, true, true);

      ClientProducer producer0 = session0.createProducer(new SimpleString(testAddress));

      ClientConsumer consumer1 = session1.createConsumer(queueName1);

      session1.start();

      final int numMessages = 10;

      final SimpleString propKey = new SimpleString("wibble");

      for (int i = 0; i < numMessages; i++)
      {
         ClientMessage message = session0.createMessage(true);

         message.putStringProperty(propKey, new SimpleString("bing"));

         message.getBodyBuffer().writeString("doo be doo be doo be doo");

         producer0.send(message);
      }

      for (int i = 0; i < numMessages; i++)
      {
         ClientMessage message = consumer1.receive(200);

         Assert.assertNotNull(message);

         SimpleString val = (SimpleString) message.getObjectProperty(propKey);

         Assert.assertEquals(new SimpleString("bong"), val);

         String sval = message.getBodyBuffer().readString();

         Assert.assertEquals("dee be dee be dee be dee", sval);

         message.acknowledge();

      }

      Assert.assertNull(consumer1.receiveImmediate());

      session0.close();

      session1.close();

      sf0.close();

      sf1.close();

      assertEquals(0, loadQueues(server0).size());


   }

   @Test
   public void testSawtoothLoad() throws Exception
   {
      Map<String, Object> server0Params = new HashMap<String, Object>();
      ActiveMQServer server0 = createClusteredServerWithParams(isNetty(), 0, true, server0Params);
      server0.getConfiguration().setThreadPoolMaxSize(10);

      Map<String, Object> server1Params = new HashMap<String, Object>();
      addTargetParameters(server1Params);
      ActiveMQServer server1 = createClusteredServerWithParams(isNetty(), 1, true, server1Params);
      server1.getConfiguration().setThreadPoolMaxSize(10);

      final String testAddress = "testAddress";
      final String queueName0 = "queue0";
      final String forwardAddress = "forwardAddress";
      final String queueName1 = "queue1";

      Map<String, TransportConfiguration> connectors = new HashMap<String, TransportConfiguration>();
      final TransportConfiguration server0tc = new TransportConfiguration(getConnector(), server0Params);
      final TransportConfiguration server1tc = new TransportConfiguration(getConnector(), server1Params);
      connectors.put(server1tc.getName(), server1tc);

      server0.getConfiguration().setConnectorConfigurations(connectors);

      ArrayList<String> staticConnectors = new ArrayList<String>();
      staticConnectors.add(server1tc.getName());

      BridgeConfiguration bridgeConfiguration = new BridgeConfiguration()
         .setName("bridge1")
         .setQueueName(queueName0)
         .setForwardingAddress(forwardAddress)
         .setRetryInterval(1000)
         .setReconnectAttemptsOnSameNode(-1)
         .setUseDuplicateDetection(false)
         .setConfirmationWindowSize(0)
         .setStaticConnectors(staticConnectors);

      List<BridgeConfiguration> bridgeConfigs = new ArrayList<BridgeConfiguration>();
      bridgeConfigs.add(bridgeConfiguration);
      server0.getConfiguration().setBridgeConfigurations(bridgeConfigs);

      CoreQueueConfiguration queueConfig0 = new CoreQueueConfiguration()
         .setAddress(testAddress)
         .setName(queueName0);
      List<CoreQueueConfiguration> queueConfigs0 = new ArrayList<CoreQueueConfiguration>();
      queueConfigs0.add(queueConfig0);
      server0.getConfiguration().setQueueConfigurations(queueConfigs0);

      CoreQueueConfiguration queueConfig1 = new CoreQueueConfiguration()
         .setAddress(forwardAddress)
         .setName(queueName1);
      List<CoreQueueConfiguration> queueConfigs1 = new ArrayList<CoreQueueConfiguration>();
      queueConfigs1.add(queueConfig1);
      server1.getConfiguration().setQueueConfigurations(queueConfigs1);

      try
      {
         server1.start();
         server0.start();

         final int numMessages = 300;

         final int totalrepeats = 3;

         final AtomicInteger errors = new AtomicInteger(0);

         // We shouldn't have more than 10K messages pending
         final Semaphore semop = new Semaphore(10000);

         class ConsumerThread extends Thread
         {
            @Override
            public void run()
            {
               try
               {
                  ServerLocator locator = addServerLocator(ActiveMQClient.createServerLocatorWithoutHA(server1tc));

                  ClientSessionFactory sf = createSessionFactory(locator);

                  ClientSession session = sf.createSession(false, false);

                  session.start();

                  ClientConsumer consumer = session.createConsumer(queueName1);

                  for (int i = 0; i < numMessages; i++)
                  {
                     ClientMessage message = consumer.receive(5000);

                     Assert.assertNotNull(message);

                     message.acknowledge();
                     semop.release();
                     if (i % 1000 == 0)
                     {
                        session.commit();
                     }
                  }

                  session.commit();

                  session.close();
                  sf.close();
                  locator.close();

               }
               catch (Throwable e)
               {
                  e.printStackTrace();
                  errors.incrementAndGet();
               }
            }
         }

         class ProducerThread extends Thread
         {
            final int nmsg;

            ProducerThread(int nmsg)
            {
               this.nmsg = nmsg;
            }

            @Override
            public void run()
            {
               ServerLocator locator = addServerLocator(ActiveMQClient.createServerLocatorWithoutHA(server0tc));

               locator.setBlockOnDurableSend(false);
               locator.setBlockOnNonDurableSend(false);

               ClientSessionFactory sf = null;

               ClientSession session = null;

               ClientProducer producer = null;

               try
               {
                  sf = createSessionFactory(locator);

                  session = sf.createSession(false, true, true);

                  producer = session.createProducer(new SimpleString(testAddress));

                  for (int i = 0; i < nmsg; i++)
                  {
                     assertEquals(0, errors.get());
                     ClientMessage message = session.createMessage(true);

                     message.putIntProperty("seq", i);


                     if (i % 100 == 0)
                     {
                        message.setPriority((byte) (RandomUtil.randomPositiveInt() % 9));
                     }
                     else
                     {
                        message.setPriority((byte) 5);
                     }

                     message.getBodyBuffer().writeBytes(new byte[50]);

                     producer.send(message);
                     assertTrue(semop.tryAcquire(1, 10, TimeUnit.SECONDS));
                  }
               }
               catch (Throwable e)
               {
                  e.printStackTrace(System.out);
                  errors.incrementAndGet();
               }
               finally
               {
                  try
                  {
                     session.close();
                     sf.close();
                     locator.close();
                  }
                  catch (Exception ignored)
                  {
                     errors.incrementAndGet();
                  }
               }
            }
         }

         for (int repeat = 0; repeat < totalrepeats; repeat++)
         {
            ArrayList<Thread> threads = new ArrayList<Thread>();

            threads.add(new ConsumerThread());
            threads.add(new ProducerThread(numMessages / 2));
            threads.add(new ProducerThread(numMessages / 2));

            for (Thread t : threads)
            {
               t.start();
            }

            for (Thread t : threads)
            {
               t.join();
            }

            assertEquals(0, errors.get());
         }
      }
      finally
      {
         try
         {
            server0.stop();
         }
         catch (Exception ignored)
         {

         }

         try
         {
            server1.stop();
         }
         catch (Exception ignored)
         {

         }
      }

      assertEquals(0, loadQueues(server0).size());


   }

   @Test
   public void testBridgeWithPaging() throws Exception
   {
      ActiveMQServer server0 = null;
      ActiveMQServer server1 = null;

      final int PAGE_MAX = 100 * 1024;

      final int PAGE_SIZE = 10 * 1024;
      try
      {

         Map<String, Object> server0Params = new HashMap<String, Object>();
         server0 = createClusteredServerWithParams(isNetty(), 0, true, PAGE_SIZE, PAGE_MAX, server0Params);

         Map<String, Object> server1Params = new HashMap<String, Object>();
         addTargetParameters(server1Params);
         server1 = createClusteredServerWithParams(isNetty(), 1, true, PAGE_SIZE, PAGE_MAX, server1Params);

         final String testAddress = "testAddress";
         final String queueName0 = "queue0";
         final String forwardAddress = "forwardAddress";
         final String queueName1 = "queue1";

         Map<String, TransportConfiguration> connectors = new HashMap<String, TransportConfiguration>();
         TransportConfiguration server0tc = new TransportConfiguration(getConnector(), server0Params);

         TransportConfiguration server1tc = new TransportConfiguration(getConnector(), server1Params);
         connectors.put(server1tc.getName(), server1tc);

         server0.getConfiguration().setConnectorConfigurations(connectors);

         server0.getConfiguration().setIDCacheSize(20000);

         ArrayList<String> staticConnectors = new ArrayList<String>();
         staticConnectors.add(server1tc.getName());

         BridgeConfiguration bridgeConfiguration = new BridgeConfiguration()
            .setName("bridge1")
            .setQueueName(queueName0)
            .setForwardingAddress(forwardAddress)
            .setRetryInterval(1)
            .setReconnectAttemptsOnSameNode(-1)
            .setUseDuplicateDetection(false)
            .setConfirmationWindowSize(1)
            .setStaticConnectors(staticConnectors);

         bridgeConfiguration.setCallTimeout(1000);

         bridgeConfiguration.setUseDuplicateDetection(true);

         List<BridgeConfiguration> bridgeConfigs = new ArrayList<BridgeConfiguration>();
         bridgeConfigs.add(bridgeConfiguration);
         server0.getConfiguration().setBridgeConfigurations(bridgeConfigs);

         CoreQueueConfiguration queueConfig0 = new CoreQueueConfiguration()
            .setAddress(testAddress)
            .setName(queueName0);
         List<CoreQueueConfiguration> queueConfigs0 = new ArrayList<CoreQueueConfiguration>();
         queueConfigs0.add(queueConfig0);
         server0.getConfiguration().setQueueConfigurations(queueConfigs0);

         CoreQueueConfiguration queueConfig1 = new CoreQueueConfiguration()
            .setAddress(forwardAddress)
            .setName(queueName1);
         List<CoreQueueConfiguration> queueConfigs1 = new ArrayList<CoreQueueConfiguration>();
         queueConfigs1.add(queueConfig1);
         server1.getConfiguration().setQueueConfigurations(queueConfigs1);

         List<String> interceptorToStop = new ArrayList<String>();
         interceptorToStop.add(StopInterceptor.class.getName());
         server1.getConfiguration().setIncomingInterceptorClassNames(interceptorToStop);

         StopInterceptor.serverToStop = server0;

         server1.start();
         server0.start();

         locator = addServerLocator(ActiveMQClient.createServerLocatorWithoutHA(server0tc, server1tc));
         ClientSessionFactory sf0 = locator.createSessionFactory(server0tc);

         ClientSessionFactory sf1 = locator.createSessionFactory(server1tc);

         ClientSession session0 = sf0.createSession(false, false, true);

         ClientSession session1 = sf1.createSession(false, true, true);

         ClientProducer producer0 = session0.createProducer(new SimpleString(testAddress));

         ClientConsumer consumer1 = session1.createConsumer(queueName1);

         session1.start();

         final int numMessages = 6000;

         final SimpleString propKey = new SimpleString("testkey");

         for (int i = 0; i < numMessages; i++)
         {
            ClientMessage message = session0.createMessage(true);
            message.getBodyBuffer().writeBytes(new byte[512]);

            message.putIntProperty(propKey, i);

            producer0.send(message);
         }

         session0.commit();

         assertTrue(StopInterceptor.latch.await(1, TimeUnit.HOURS));

         StopInterceptor.thread.join(15000);

         if (StopInterceptor.thread.isAlive())
         {
            System.out.println(threadDump("Still alive, stop didn't work!!!"));
            fail("Thread that should restart the server still alive");
         }


         // Restarting the server
         server0.start();

         HashMap<Integer, AtomicInteger> receivedMsg = new HashMap<Integer, AtomicInteger>();

         for (int i = 0; i < numMessages; i++)
         {
            ClientMessage message = consumer1.receive(5000);

            if (message == null)
            {
               break;
            }

            Integer msgKey = message.getIntProperty(propKey);

            AtomicInteger msgCount = receivedMsg.get(msgKey);

            if (msgKey.intValue() != i)
            {
               System.err.println("Message " + msgCount + " received out of order, expected to be " + i + " it's acceptable but not the ideal!");
            }

            if (msgCount == null)
            {
               msgCount = new AtomicInteger();
               receivedMsg.put(msgKey, msgCount);
            }

            msgCount.incrementAndGet();

            if (i % 500 == 0) System.out.println("received " + i);
         }

         boolean failed = false;

         if (consumer1.receiveImmediate() != null)
         {
            System.err.println("Unexpected message received");
            failed = true;
         }

         for (int i = 0; i < numMessages; i++)
         {
            AtomicInteger msgCount = receivedMsg.get(Integer.valueOf(i));
            if (msgCount == null)
            {
               System.err.println("Msg " + i + " wasn't received");
               failed = true;
            }
            else if (msgCount.get() > 1)
            {
               System.err.println("msg " + i + " was received " + msgCount.get() + " times");
               failed = true;
            }

         }

         assertFalse("Test failed", failed);

         session0.close();

         session1.close();

         sf0.close();

         sf1.close();

      }
      finally
      {
         if (locator != null)
         {
            locator.close();
         }
         try
         {
            server0.stop();
         }
         catch (Throwable ignored)
         {
         }

         try
         {
            server1.stop();
         }
         catch (Throwable ignored)
         {
         }
      }


      assertEquals(0, loadQueues(server0).size());


   }


   // Stops a server after 100 messages received
   public static class StopInterceptor implements Interceptor
   {
      static ActiveMQServer serverToStop;

      static Thread thread;

      static final ReusableLatch latch = new ReusableLatch(0);


      public static void reset()
      {
         latch.setCount(1);
         serverToStop = null;
         count = 0;
         thread = null;
      }

      static int count = 0;

      @Override
      public synchronized boolean intercept(Packet packet, RemotingConnection connection) throws ActiveMQException
      {

         if (packet instanceof SessionSendMessage && count == 1000)
         {
            System.out.println("Going to kill the server");
         }
         if (packet instanceof SessionSendMessage && ++count == 5000)
         {
            try
            {
               System.out.println("Stopping server after " + count + " messages");

               thread = new Thread("***Server Restarter***")
               {

                  public void run()
                  {
                     try
                     {
                        System.out.println("Stopping server");
                        latch.countDown();
                        serverToStop.stop(false);
                     }
                     catch (Exception e)
                     {
                        e.printStackTrace();
                     }
                  }
               };

               thread.start();

               latch.await();
               return true;
            }
            catch (Exception e)
            {
               e.printStackTrace();
            }
         }
         return true;
      }
   }

   @Test
   public void testBridgeWithLargeMessage() throws Exception
   {
      ActiveMQServer server0 = null;
      ActiveMQServer server1 = null;

      final int PAGE_MAX = 1024 * 1024;

      final int PAGE_SIZE = 10 * 1024;
      ServerLocator locator = null;
      try
      {

         Map<String, Object> server0Params = new HashMap<String, Object>();
         server0 = createClusteredServerWithParams(isNetty(), 0, true, PAGE_SIZE, PAGE_MAX, server0Params);

         Map<String, Object> server1Params = new HashMap<String, Object>();
         addTargetParameters(server1Params);
         server1 = createClusteredServerWithParams(isNetty(), 1, true, server1Params);

         final String testAddress = "testAddress";
         final String queueName0 = "queue0";
         final String forwardAddress = "forwardAddress";
         final String queueName1 = "queue1";

         Map<String, TransportConfiguration> connectors = new HashMap<String, TransportConfiguration>();
         TransportConfiguration server0tc = new TransportConfiguration(getConnector(), server0Params);

         TransportConfiguration server1tc = new TransportConfiguration(getConnector(), server1Params);
         connectors.put(server1tc.getName(), server1tc);

         server0.getConfiguration().setConnectorConfigurations(connectors);

         ArrayList<String> staticConnectors = new ArrayList<String>();
         staticConnectors.add(server1tc.getName());

         BridgeConfiguration bridgeConfiguration = new BridgeConfiguration()
            .setName("bridge1")
            .setQueueName(queueName0)
            .setForwardingAddress(forwardAddress)
            .setRetryInterval(1000)
            .setReconnectAttemptsOnSameNode(-1)
            .setUseDuplicateDetection(false)
            .setConfirmationWindowSize(1024)
            .setStaticConnectors(staticConnectors);

         List<BridgeConfiguration> bridgeConfigs = new ArrayList<BridgeConfiguration>();
         bridgeConfigs.add(bridgeConfiguration);
         server0.getConfiguration().setBridgeConfigurations(bridgeConfigs);

         CoreQueueConfiguration queueConfig0 = new CoreQueueConfiguration()
            .setAddress(testAddress)
            .setName(queueName0);
         List<CoreQueueConfiguration> queueConfigs0 = new ArrayList<CoreQueueConfiguration>();
         queueConfigs0.add(queueConfig0);
         server0.getConfiguration().setQueueConfigurations(queueConfigs0);

         CoreQueueConfiguration queueConfig1 = new CoreQueueConfiguration()
            .setAddress(forwardAddress)
            .setName(queueName1);
         List<CoreQueueConfiguration> queueConfigs1 = new ArrayList<CoreQueueConfiguration>();
         queueConfigs1.add(queueConfig1);
         server1.getConfiguration().setQueueConfigurations(queueConfigs1);

         server1.start();
         server0.start();

         locator = addServerLocator(ActiveMQClient.createServerLocatorWithoutHA(server0tc, server1tc));
         ClientSessionFactory sf0 = locator.createSessionFactory(server0tc);

         ClientSessionFactory sf1 = locator.createSessionFactory(server1tc);

         ClientSession session0 = sf0.createSession(false, true, true);

         ClientSession session1 = sf1.createSession(false, true, true);

         ClientProducer producer0 = session0.createProducer(new SimpleString(testAddress));

         ClientConsumer consumer1 = session1.createConsumer(queueName1);

         session1.start();

         final int numMessages = 50;

         final SimpleString propKey = new SimpleString("testkey");

         final int LARGE_MESSAGE_SIZE = 1024;
         for (int i = 0; i < numMessages; i++)
         {
            ClientMessage message = session0.createMessage(true);
            message.setBodyInputStream(createFakeLargeStream(LARGE_MESSAGE_SIZE));

            message.putIntProperty(propKey, i);

            producer0.send(message);
         }

         session0.commit();

         for (int i = 0; i < numMessages; i++)
         {
            ClientMessage message = consumer1.receive(5000);

            Assert.assertNotNull(message);

            Assert.assertEquals(i, message.getObjectProperty(propKey));

            ActiveMQBuffer buff = message.getBodyBuffer();

            for (int posMsg = 0; posMsg < LARGE_MESSAGE_SIZE; posMsg++)
            {
               assertEquals(getSamplebyte(posMsg), buff.readByte());
            }

            message.acknowledge();
         }

         session1.commit();

         Assert.assertNull(consumer1.receiveImmediate());

         session0.close();

         session1.close();

         sf0.close();

         sf1.close();


      }
      finally
      {
         if (locator != null)
         {
            locator.close();
         }
         try
         {
            server0.stop();
         }
         catch (Throwable ignored)
         {
         }

         try
         {
            server1.stop();
         }
         catch (Throwable ignored)
         {
         }
      }

      assertEquals(0, loadQueues(server0).size());
   }

   @Test
   public void testNullForwardingAddress() throws Exception
   {
      Map<String, Object> server0Params = new HashMap<String, Object>();
      server0 = createClusteredServerWithParams(isNetty(), 0, false, server0Params);

      Map<String, Object> server1Params = new HashMap<String, Object>();
      addTargetParameters(server1Params);
      server1 = createClusteredServerWithParams(isNetty(), 1, false, server1Params);

      final String testAddress = "testAddress";
      final String queueName0 = "queue0";
      final String queueName1 = "queue1";

      Map<String, TransportConfiguration> connectors = new HashMap<String, TransportConfiguration>();
      TransportConfiguration server0tc = new TransportConfiguration(getConnector(), server0Params);

      TransportConfiguration server1tc = new TransportConfiguration(getConnector(), server1Params);
      connectors.put(server1tc.getName(), server1tc);

      server0.getConfiguration().setConnectorConfigurations(connectors);

      final int messageSize = 1024;

      final int numMessages = 10;

      ArrayList<String> staticConnectors = new ArrayList<String>();
      staticConnectors.add(server1tc.getName());
      // do not set forwarding address (defaults to null) to use messages' original address
      BridgeConfiguration bridgeConfiguration = new BridgeConfiguration()
         .setName("bridge1")
         .setQueueName(queueName0)
         .setRetryInterval(1000)
         .setReconnectAttemptsOnSameNode(-1)
         .setUseDuplicateDetection(false)
         .setConfirmationWindowSize(numMessages * messageSize / 2)
         .setStaticConnectors(staticConnectors);

      List<BridgeConfiguration> bridgeConfigs = new ArrayList<BridgeConfiguration>();
      bridgeConfigs.add(bridgeConfiguration);
      server0.getConfiguration().setBridgeConfigurations(bridgeConfigs);

      CoreQueueConfiguration queueConfig0 = new CoreQueueConfiguration()
         .setAddress(testAddress)
         .setName(queueName0);
      List<CoreQueueConfiguration> queueConfigs0 = new ArrayList<CoreQueueConfiguration>();
      queueConfigs0.add(queueConfig0);
      server0.getConfiguration().setQueueConfigurations(queueConfigs0);

      // on server #1, we bind queueName1 to same address testAddress
      CoreQueueConfiguration queueConfig1 = new CoreQueueConfiguration()
         .setAddress(testAddress)
         .setName(queueName1);
      List<CoreQueueConfiguration> queueConfigs1 = new ArrayList<CoreQueueConfiguration>();
      queueConfigs1.add(queueConfig1);
      server1.getConfiguration().setQueueConfigurations(queueConfigs1);

      server1.start();
      server0.start();

      locator = addServerLocator(ActiveMQClient.createServerLocatorWithoutHA(server0tc, server1tc));
      ClientSessionFactory sf0 = locator.createSessionFactory(server0tc);

      ClientSessionFactory sf1 = locator.createSessionFactory(server1tc);

      ClientSession session0 = sf0.createSession(false, true, true);

      ClientSession session1 = sf1.createSession(false, true, true);

      ClientProducer producer0 = session0.createProducer(new SimpleString(testAddress));

      ClientConsumer consumer1 = session1.createConsumer(queueName1);

      session1.start();

      final byte[] bytes = new byte[messageSize];

      final SimpleString propKey = new SimpleString("testkey");

      for (int i = 0; i < numMessages; i++)
      {
         ClientMessage message = session0.createMessage(true);

         message.putIntProperty(propKey, i);

         message.getBodyBuffer().writeBytes(bytes);

         producer0.send(message);
      }

      for (int i = 0; i < numMessages; i++)
      {
         ClientMessage message = consumer1.receive(200);

         Assert.assertNotNull(message);

         Assert.assertEquals(i, message.getObjectProperty(propKey));

         message.acknowledge();
      }

      Assert.assertNull(consumer1.receiveImmediate());

      session0.close();

      session1.close();

      sf0.close();

      sf1.close();
      closeFields();

      assertEquals(0, loadQueues(server0).size());


   }
}
