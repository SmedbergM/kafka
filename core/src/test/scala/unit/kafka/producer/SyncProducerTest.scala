/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.producer

import java.net.SocketTimeoutException
import java.util.Properties
import junit.framework.Assert
import kafka.admin.CreateTopicCommand
import kafka.common.{ErrorMapping}
import kafka.integration.KafkaServerTestHarness
import kafka.message._
import kafka.server.KafkaConfig
import kafka.utils._
import org.junit.Test
import org.scalatest.junit.JUnit3Suite
import kafka.api.{ProducerResponseStatus, ProducerRequestPartitionData}
import kafka.common.{TopicAndPartition, ErrorMapping}

class SyncProducerTest extends JUnit3Suite with KafkaServerTestHarness {
  private var messageBytes =  new Array[Byte](2);
  val configs = List(new KafkaConfig(TestUtils.createBrokerConfigs(1).head))
  val zookeeperConnect = TestZKUtils.zookeeperConnect

  @Test
  def testReachableServer() {
    val server = servers.head
    val props = new Properties()
    props.put("host", "localhost")
    props.put("port", server.socketServer.port.toString)
    props.put("buffer.size", "102400")
    props.put("connect.timeout.ms", "500")
    props.put("reconnect.interval", "1000")
    val producer = new SyncProducer(new SyncProducerConfig(props))
    val firstStart = SystemTime.milliseconds
    try {
      val response = producer.send(TestUtils.produceRequest("test", 0, new ByteBufferMessageSet(compressionCodec = NoCompressionCodec, messages = new Message(messageBytes))))
      Assert.assertNotNull(response)
    } catch {
      case e: Exception => Assert.fail("Unexpected failure sending message to broker. " + e.getMessage)
    }
    val firstEnd = SystemTime.milliseconds
    Assert.assertTrue((firstEnd-firstStart) < 500)
    val secondStart = SystemTime.milliseconds
    try {
      val response = producer.send(TestUtils.produceRequest("test", 0, new ByteBufferMessageSet(compressionCodec = NoCompressionCodec, messages = new Message(messageBytes))))
      Assert.assertNotNull(response)
    } catch {
      case e: Exception => Assert.fail("Unexpected failure sending message to broker. " + e.getMessage)
    }
    val secondEnd = SystemTime.milliseconds
    Assert.assertTrue((secondEnd-secondStart) < 500)
    try {
      val response = producer.send(TestUtils.produceRequest("test", 0, new ByteBufferMessageSet(compressionCodec = NoCompressionCodec, messages = new Message(messageBytes))))
      Assert.assertNotNull(response)
    } catch {
      case e: Exception => Assert.fail("Unexpected failure sending message to broker. " + e.getMessage)
    }
  }

  @Test
  def testEmptyProduceRequest() {
    val server = servers.head
    val props = new Properties()
    props.put("host", "localhost")
    props.put("port", server.socketServer.port.toString)
    props.put("buffer.size", "102400")
    props.put("connect.timeout.ms", "300")
    props.put("reconnect.interval", "500")
    props.put("max.message.size", "100")
    val correlationId = SyncProducerConfig.DefaultCorrelationId
    val clientId = SyncProducerConfig.DefaultClientId
    val ackTimeoutMs = SyncProducerConfig.DefaultAckTimeoutMs
    val ack = SyncProducerConfig.DefaultRequiredAcks
    val emptyRequest = new kafka.api.ProducerRequest(correlationId, clientId, ack, ackTimeoutMs, Map[TopicAndPartition, ProducerRequestPartitionData]())

    val producer = new SyncProducer(new SyncProducerConfig(props))
    val response = producer.send(emptyRequest)
    Assert.assertTrue(!response.hasError && response.status.size == 0)
  }

  @Test
  def testMessageSizeTooLarge() {
    val server = servers.head
    val props = new Properties()
    props.put("host", "localhost")
    props.put("port", server.socketServer.port.toString)
    props.put("buffer.size", "102400")
    props.put("connect.timeout.ms", "300")
    props.put("reconnect.interval", "500")
    val producer = new SyncProducer(new SyncProducerConfig(props))
    CreateTopicCommand.createTopic(zkClient, "test", 1, 1)
    TestUtils.waitUntilLeaderIsElectedOrChanged(zkClient, "test", 0, 500)

    val message1 = new Message(new Array[Byte](configs(0).maxMessageSize + 1))
    val messageSet1 = new ByteBufferMessageSet(compressionCodec = NoCompressionCodec, messages = message1)
    val response1 = producer.send(TestUtils.produceRequest("test", 0, messageSet1))

    Assert.assertEquals(1, response1.status.count(_._2.error != ErrorMapping.NoError))
    Assert.assertEquals(ErrorMapping.MessageSizeTooLargeCode, response1.status(TopicAndPartition("test", 0)).error)
    Assert.assertEquals(-1L, response1.status(TopicAndPartition("test", 0)).offset)

    val safeSize = configs(0).maxMessageSize - Message.MessageOverhead - MessageSet.LogOverhead - 1
    val message2 = new Message(new Array[Byte](safeSize))
    val messageSet2 = new ByteBufferMessageSet(compressionCodec = NoCompressionCodec, messages = message2)
    val response2 = producer.send(TestUtils.produceRequest("test", 0, messageSet2))

    Assert.assertEquals(1, response1.status.count(_._2.error != ErrorMapping.NoError))
    Assert.assertEquals(ErrorMapping.NoError, response2.status(TopicAndPartition("test", 0)).error)
    Assert.assertEquals(0, response2.status(TopicAndPartition("test", 0)).offset)
  }

  @Test
  def testProduceCorrectlyReceivesResponse() {
    val server = servers.head
    val props = new Properties()
    props.put("host", "localhost")
    props.put("port", server.socketServer.port.toString)
    props.put("buffer.size", "102400")
    props.put("connect.timeout.ms", "300")
    props.put("reconnect.interval", "500")
    props.put("max.message.size", "100")

    val producer = new SyncProducer(new SyncProducerConfig(props))
    val messages = new ByteBufferMessageSet(NoCompressionCodec, new Message(messageBytes))

    // #1 - test that we get an error when partition does not belong to broker in response
    val request = TestUtils.produceRequestWithAcks(Array("topic1", "topic2", "topic3"), Array(0), messages, 1)
    val response = producer.send(request)

    Assert.assertNotNull(response)
    Assert.assertEquals(request.correlationId, response.correlationId)
    Assert.assertEquals(3, response.status.size)
    response.status.values.foreach {
      case ProducerResponseStatus(error, nextOffset) =>
        Assert.assertEquals(ErrorMapping.UnknownTopicOrPartitionCode.toShort, error)
        Assert.assertEquals(-1L, nextOffset)
    }

    // #2 - test that we get correct offsets when partition is owned by broker
    CreateTopicCommand.createTopic(zkClient, "topic1", 1, 1)
    TestUtils.waitUntilLeaderIsElectedOrChanged(zkClient, "topic1", 0, 500)
    CreateTopicCommand.createTopic(zkClient, "topic3", 1, 1)
    TestUtils.waitUntilLeaderIsElectedOrChanged(zkClient, "topic3", 0, 500)

    val response2 = producer.send(request)
    Assert.assertNotNull(response2)
    Assert.assertEquals(request.correlationId, response2.correlationId)
    Assert.assertEquals(3, response2.status.size)

    // the first and last message should have been accepted by broker
    Assert.assertEquals(ErrorMapping.NoError, response2.status(TopicAndPartition("topic1", 0)).error)
    Assert.assertEquals(ErrorMapping.NoError, response2.status(TopicAndPartition("topic3", 0)).error)
    Assert.assertEquals(0, response2.status(TopicAndPartition("topic1", 0)).offset)
    Assert.assertEquals(0, response2.status(TopicAndPartition("topic3", 0)).offset)

    // the middle message should have been rejected because broker doesn't lead partition
    Assert.assertEquals(ErrorMapping.UnknownTopicOrPartitionCode.toShort,
                        response2.status(TopicAndPartition("topic2", 0)).error)
    Assert.assertEquals(-1, response2.status(TopicAndPartition("topic2", 0)).offset)
  }

  @Test
  def testProducerCanTimeout() {
    val timeoutMs = 500

    val server = servers.head
    val props = new Properties()
    props.put("host", "localhost")
    props.put("port", server.socketServer.port.toString)
    props.put("buffer.size", "102400")
    props.put("producer.request.timeout.ms", String.valueOf(timeoutMs))
    val producer = new SyncProducer(new SyncProducerConfig(props))

    val messages = new ByteBufferMessageSet(NoCompressionCodec, new Message(messageBytes))
    val request = TestUtils.produceRequest("topic1", 0, messages)

    // stop IO threads and request handling, but leave networking operational
    // any requests should be accepted and queue up, but not handled
    server.requestHandlerPool.shutdown()

    val t1 = SystemTime.milliseconds
    try {
      producer.send(request)
      Assert.fail("Should have received timeout exception since request handling is stopped.")
    } catch {
      case e: SocketTimeoutException => /* success */
      case e => Assert.fail("Unexpected exception when expecting timeout: " + e)
    }
    val t2 = SystemTime.milliseconds

    // make sure we don't wait fewer than timeoutMs for a response
    Assert.assertTrue((t2-t1) >= timeoutMs)
  }
}