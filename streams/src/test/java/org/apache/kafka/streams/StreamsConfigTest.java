/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.streams.errors.StreamsException;
import org.apache.kafka.streams.processor.FailOnInvalidTimestamp;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.apache.kafka.streams.processor.internals.StreamsPartitionAssignor;
import org.apache.kafka.streams.processor.internals.testutil.LogCaptureAppender;
import org.hamcrest.CoreMatchers;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static org.apache.kafka.common.requests.IsolationLevel.READ_COMMITTED;
import static org.apache.kafka.common.requests.IsolationLevel.READ_UNCOMMITTED;
import static org.apache.kafka.streams.StreamsConfig.EXACTLY_ONCE;
import static org.apache.kafka.streams.StreamsConfig.TOPOLOGY_OPTIMIZATION;
import static org.apache.kafka.streams.StreamsConfig.adminClientPrefix;
import static org.apache.kafka.streams.StreamsConfig.consumerPrefix;
import static org.apache.kafka.streams.StreamsConfig.producerPrefix;
import static org.apache.kafka.test.StreamsTestUtils.getStreamsConfig;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StreamsConfigTest {

    private final Properties props = new Properties();
    private StreamsConfig streamsConfig;

    private final String groupId = "example-application";
    private final String clientId = "client";
    private final int threadIdx = 1;

    @Before
    public void setUp() {
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "streams-config-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put("key.deserializer.encoding", "UTF8");
        props.put("value.deserializer.encoding", "UTF-16");
        streamsConfig = new StreamsConfig(props);
    }

    @Test(expected = ConfigException.class)
    public void testIllegalMetricsRecordingLevel() {
        props.put(StreamsConfig.METRICS_RECORDING_LEVEL_CONFIG, "illegalConfig");
        new StreamsConfig(props);
    }

    @Test
    public void testOsDefaultSocketBufferSizes() {
        props.put(StreamsConfig.SEND_BUFFER_CONFIG, CommonClientConfigs.RECEIVE_BUFFER_LOWER_BOUND);
        props.put(StreamsConfig.RECEIVE_BUFFER_CONFIG, CommonClientConfigs.RECEIVE_BUFFER_LOWER_BOUND);
        new StreamsConfig(props);
    }

    @Test(expected = ConfigException.class)
    public void testInvalidSocketSendBufferSize() {
        props.put(StreamsConfig.SEND_BUFFER_CONFIG, -2);
        new StreamsConfig(props);
    }

    @Test(expected = ConfigException.class)
    public void testInvalidSocketReceiveBufferSize() {
        props.put(StreamsConfig.RECEIVE_BUFFER_CONFIG, -2);
        new StreamsConfig(props);
    }

    @Test(expected = ConfigException.class)
    public void shouldThrowExceptionIfApplicationIdIsNotSet() {
        props.remove(StreamsConfig.APPLICATION_ID_CONFIG);
        new StreamsConfig(props);
    }

    @Test(expected = ConfigException.class)
    public void shouldThrowExceptionIfBootstrapServersIsNotSet() {
        props.remove(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG);
        new StreamsConfig(props);
    }

    @Test
    public void testGetProducerConfigs() {
        final Map<String, Object> returnedProps = streamsConfig.getProducerConfigs(clientId);
        assertThat(returnedProps.get(ProducerConfig.CLIENT_ID_CONFIG), equalTo(clientId));
        assertThat(returnedProps.get(ProducerConfig.LINGER_MS_CONFIG), equalTo("100"));
    }

    @Test
    public void testGetConsumerConfigs() {
        final Map<String, Object> returnedProps = streamsConfig.getMainConsumerConfigs(groupId, clientId, threadIdx);
        assertThat(returnedProps.get(ConsumerConfig.CLIENT_ID_CONFIG), equalTo(clientId));
        assertThat(returnedProps.get(ConsumerConfig.GROUP_ID_CONFIG), equalTo(groupId));
        assertThat(returnedProps.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG), equalTo("1000"));
        assertNull(returnedProps.get(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG));
    }

    @Test
    public void testGetGroupInstanceIdConfigs() {
        props.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, "group-instance-id");
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> returnedProps = streamsConfig.getMainConsumerConfigs(groupId, clientId, threadIdx);
        assertThat(returnedProps.get(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG), equalTo("group-instance-id-1"));
    }

    @Test
    public void consumerConfigMustContainStreamPartitionAssignorConfig() {
        props.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 42);
        props.put(StreamsConfig.NUM_STANDBY_REPLICAS_CONFIG, 1);
        props.put(StreamsConfig.WINDOW_STORE_CHANGE_LOG_ADDITIONAL_RETENTION_MS_CONFIG, 7L);
        props.put(StreamsConfig.APPLICATION_SERVER_CONFIG, "dummy:host");
        props.put(StreamsConfig.RETRIES_CONFIG, 10);
        props.put(StreamsConfig.adminClientPrefix(StreamsConfig.RETRIES_CONFIG), 5);
        props.put(StreamsConfig.topicPrefix(TopicConfig.SEGMENT_BYTES_CONFIG), 100);
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> returnedProps = streamsConfig.getMainConsumerConfigs(groupId, clientId, threadIdx);

        assertEquals(42, returnedProps.get(StreamsConfig.REPLICATION_FACTOR_CONFIG));
        assertEquals(1, returnedProps.get(StreamsConfig.NUM_STANDBY_REPLICAS_CONFIG));
        assertEquals(StreamsPartitionAssignor.class.getName(), returnedProps.get(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG));
        assertEquals(7L, returnedProps.get(StreamsConfig.WINDOW_STORE_CHANGE_LOG_ADDITIONAL_RETENTION_MS_CONFIG));
        assertEquals("dummy:host", returnedProps.get(StreamsConfig.APPLICATION_SERVER_CONFIG));
        assertNull(returnedProps.get(StreamsConfig.RETRIES_CONFIG));
        assertEquals(5, returnedProps.get(StreamsConfig.adminClientPrefix(StreamsConfig.RETRIES_CONFIG)));
        assertEquals(100, returnedProps.get(StreamsConfig.topicPrefix(TopicConfig.SEGMENT_BYTES_CONFIG)));
    }

    @Test
    public void consumerConfigShouldContainAdminClientConfigsForRetriesAndRetryBackOffMsWithAdminPrefix() {
        props.put(StreamsConfig.adminClientPrefix(StreamsConfig.RETRIES_CONFIG), 20);
        props.put(StreamsConfig.adminClientPrefix(StreamsConfig.RETRY_BACKOFF_MS_CONFIG), 200L);
        props.put(StreamsConfig.RETRIES_CONFIG, 10);
        props.put(StreamsConfig.RETRY_BACKOFF_MS_CONFIG, 100L);
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> returnedProps = streamsConfig.getMainConsumerConfigs(groupId, clientId, threadIdx);

        assertEquals(20, returnedProps.get(StreamsConfig.adminClientPrefix(StreamsConfig.RETRIES_CONFIG)));
        assertEquals(200L, returnedProps.get(StreamsConfig.adminClientPrefix(StreamsConfig.RETRY_BACKOFF_MS_CONFIG)));
    }

    @Test
    public void testGetMainConsumerConfigsWithMainConsumerOverridenPrefix() {
        props.put(StreamsConfig.consumerPrefix(ConsumerConfig.MAX_POLL_RECORDS_CONFIG), "5");
        props.put(StreamsConfig.mainConsumerPrefix(ConsumerConfig.MAX_POLL_RECORDS_CONFIG), "50");
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> returnedProps = streamsConfig.getMainConsumerConfigs(groupId, clientId, threadIdx);
        assertEquals("50", returnedProps.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG));
    }

    @Test
    public void testGetRestoreConsumerConfigs() {
        final Map<String, Object> returnedProps = streamsConfig.getRestoreConsumerConfigs(clientId);
        assertEquals(returnedProps.get(ConsumerConfig.CLIENT_ID_CONFIG), clientId);
        assertNull(returnedProps.get(ConsumerConfig.GROUP_ID_CONFIG));
    }

    @Test
    public void defaultSerdeShouldBeConfigured() {
        final Map<String, Object> serializerConfigs = new HashMap<>();
        serializerConfigs.put("key.serializer.encoding", "UTF8");
        serializerConfigs.put("value.serializer.encoding", "UTF-16");
        final Serializer<String> serializer = Serdes.String().serializer();

        final String str = "my string for testing";
        final String topic = "my topic";

        serializer.configure(serializerConfigs, true);
        assertEquals("Should get the original string after serialization and deserialization with the configured encoding",
                str, streamsConfig.defaultKeySerde().deserializer().deserialize(topic, serializer.serialize(topic, str)));

        serializer.configure(serializerConfigs, false);
        assertEquals("Should get the original string after serialization and deserialization with the configured encoding",
                str, streamsConfig.defaultValueSerde().deserializer().deserialize(topic, serializer.serialize(topic, str)));
    }

    @Test
    public void shouldSupportMultipleBootstrapServers() {
        final List<String> expectedBootstrapServers = Arrays.asList("broker1:9092", "broker2:9092");
        final String bootstrapServersString = Utils.join(expectedBootstrapServers, ",");
        final Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "irrelevant");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServersString);
        final StreamsConfig config = new StreamsConfig(props);

        final List<String> actualBootstrapServers = config.getList(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG);
        assertEquals(expectedBootstrapServers, actualBootstrapServers);
    }

    @Test
    public void shouldSupportPrefixedConsumerConfigs() {
        props.put(consumerPrefix(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG), "earliest");
        props.put(consumerPrefix(ConsumerConfig.METRICS_NUM_SAMPLES_CONFIG), 1);
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> consumerConfigs = streamsConfig.getMainConsumerConfigs(groupId, clientId, threadIdx);
        assertEquals("earliest", consumerConfigs.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
        assertEquals(1, consumerConfigs.get(ConsumerConfig.METRICS_NUM_SAMPLES_CONFIG));
    }

    @Test
    public void shouldSupportPrefixedRestoreConsumerConfigs() {
        props.put(consumerPrefix(ConsumerConfig.METRICS_NUM_SAMPLES_CONFIG), 1);
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> consumerConfigs = streamsConfig.getRestoreConsumerConfigs(clientId);
        assertEquals(1, consumerConfigs.get(ConsumerConfig.METRICS_NUM_SAMPLES_CONFIG));
    }

    @Test
    public void shouldSupportPrefixedPropertiesThatAreNotPartOfConsumerConfig() {
        props.put(consumerPrefix("interceptor.statsd.host"), "host");
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> consumerConfigs = streamsConfig.getMainConsumerConfigs(groupId, clientId, threadIdx);
        assertEquals("host", consumerConfigs.get("interceptor.statsd.host"));
    }

    @Test
    public void shouldSupportPrefixedPropertiesThatAreNotPartOfRestoreConsumerConfig() {
        props.put(consumerPrefix("interceptor.statsd.host"), "host");
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> consumerConfigs = streamsConfig.getRestoreConsumerConfigs(clientId);
        assertEquals("host", consumerConfigs.get("interceptor.statsd.host"));
    }

    @Test
    public void shouldSupportPrefixedPropertiesThatAreNotPartOfProducerConfig() {
        props.put(producerPrefix("interceptor.statsd.host"), "host");
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> producerConfigs = streamsConfig.getProducerConfigs(clientId);
        assertEquals("host", producerConfigs.get("interceptor.statsd.host"));
    }

    @Test
    public void shouldSupportPrefixedProducerConfigs() {
        props.put(producerPrefix(ProducerConfig.BUFFER_MEMORY_CONFIG), 10);
        props.put(producerPrefix(ConsumerConfig.METRICS_NUM_SAMPLES_CONFIG), 1);
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> configs = streamsConfig.getProducerConfigs(clientId);
        assertEquals(10, configs.get(ProducerConfig.BUFFER_MEMORY_CONFIG));
        assertEquals(1, configs.get(ProducerConfig.METRICS_NUM_SAMPLES_CONFIG));
    }

    @Test
    public void shouldBeSupportNonPrefixedConsumerConfigs() {
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.METRICS_NUM_SAMPLES_CONFIG, 1);
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> consumerConfigs = streamsConfig.getMainConsumerConfigs(groupId, clientId, threadIdx);
        assertEquals("earliest", consumerConfigs.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
        assertEquals(1, consumerConfigs.get(ConsumerConfig.METRICS_NUM_SAMPLES_CONFIG));
    }

    @Test
    public void shouldBeSupportNonPrefixedRestoreConsumerConfigs() {
        props.put(ConsumerConfig.METRICS_NUM_SAMPLES_CONFIG, 1);
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> consumerConfigs = streamsConfig.getRestoreConsumerConfigs(groupId);
        assertEquals(1, consumerConfigs.get(ConsumerConfig.METRICS_NUM_SAMPLES_CONFIG));
    }

    @Test
    public void shouldSupportNonPrefixedProducerConfigs() {
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 10);
        props.put(ConsumerConfig.METRICS_NUM_SAMPLES_CONFIG, 1);
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> configs = streamsConfig.getProducerConfigs(clientId);
        assertEquals(10, configs.get(ProducerConfig.BUFFER_MEMORY_CONFIG));
        assertEquals(1, configs.get(ProducerConfig.METRICS_NUM_SAMPLES_CONFIG));
    }

    @Test
    public void shouldForwardCustomConfigsWithNoPrefixToAllClients() {
        props.put("custom.property.host", "host");
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> consumerConfigs = streamsConfig.getMainConsumerConfigs(groupId, clientId, threadIdx);
        final Map<String, Object> restoreConsumerConfigs = streamsConfig.getRestoreConsumerConfigs(clientId);
        final Map<String, Object> producerConfigs = streamsConfig.getProducerConfigs(clientId);
        final Map<String, Object> adminConfigs = streamsConfig.getAdminConfigs(clientId);
        assertEquals("host", consumerConfigs.get("custom.property.host"));
        assertEquals("host", restoreConsumerConfigs.get("custom.property.host"));
        assertEquals("host", producerConfigs.get("custom.property.host"));
        assertEquals("host", adminConfigs.get("custom.property.host"));
    }

    @Test
    public void shouldOverrideNonPrefixedCustomConfigsWithPrefixedConfigs() {
        props.put("custom.property.host", "host0");
        props.put(consumerPrefix("custom.property.host"), "host1");
        props.put(producerPrefix("custom.property.host"), "host2");
        props.put(adminClientPrefix("custom.property.host"), "host3");
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> consumerConfigs = streamsConfig.getMainConsumerConfigs(groupId, clientId, threadIdx);
        final Map<String, Object> restoreConsumerConfigs = streamsConfig.getRestoreConsumerConfigs(clientId);
        final Map<String, Object> producerConfigs = streamsConfig.getProducerConfigs(clientId);
        final Map<String, Object> adminConfigs = streamsConfig.getAdminConfigs(clientId);
        assertEquals("host1", consumerConfigs.get("custom.property.host"));
        assertEquals("host1", restoreConsumerConfigs.get("custom.property.host"));
        assertEquals("host2", producerConfigs.get("custom.property.host"));
        assertEquals("host3", adminConfigs.get("custom.property.host"));
    }

    @Test
    public void shouldSupportNonPrefixedAdminConfigs() {
        props.put(AdminClientConfig.RETRIES_CONFIG, 10);
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> configs = streamsConfig.getAdminConfigs(clientId);
        assertEquals(10, configs.get(AdminClientConfig.RETRIES_CONFIG));
    }

    @Test(expected = StreamsException.class)
    public void shouldThrowStreamsExceptionIfKeySerdeConfigFails() {
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, MisconfiguredSerde.class);
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        streamsConfig.defaultKeySerde();
    }

    @Test(expected = StreamsException.class)
    public void shouldThrowStreamsExceptionIfValueSerdeConfigFails() {
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, MisconfiguredSerde.class);
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        streamsConfig.defaultValueSerde();
    }

    @Test
    public void shouldOverrideStreamsDefaultConsumerConfigs() {
        props.put(StreamsConfig.consumerPrefix(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG), "latest");
        props.put(StreamsConfig.consumerPrefix(ConsumerConfig.MAX_POLL_RECORDS_CONFIG), "10");
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> consumerConfigs = streamsConfig.getMainConsumerConfigs(groupId, clientId, threadIdx);
        assertEquals("latest", consumerConfigs.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
        assertEquals("10", consumerConfigs.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG));
    }

    @Test
    public void shouldOverrideStreamsDefaultProducerConfigs() {
        props.put(StreamsConfig.producerPrefix(ProducerConfig.LINGER_MS_CONFIG), "10000");
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> producerConfigs = streamsConfig.getProducerConfigs(clientId);
        assertEquals("10000", producerConfigs.get(ProducerConfig.LINGER_MS_CONFIG));
    }

    @Test
    public void shouldOverrideStreamsDefaultConsumerConifgsOnRestoreConsumer() {
        props.put(StreamsConfig.consumerPrefix(ConsumerConfig.MAX_POLL_RECORDS_CONFIG), "10");
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> consumerConfigs = streamsConfig.getRestoreConsumerConfigs(clientId);
        assertEquals("10", consumerConfigs.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG));
    }

    @Test
    public void shouldResetToDefaultIfConsumerAutoCommitIsOverridden() {
        props.put(StreamsConfig.consumerPrefix(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG), "true");
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> consumerConfigs = streamsConfig.getMainConsumerConfigs("a", "b", threadIdx);
        assertEquals("false", consumerConfigs.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
    }

    @Test
    public void shouldResetToDefaultIfRestoreConsumerAutoCommitIsOverridden() {
        props.put(StreamsConfig.consumerPrefix(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG), "true");
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> consumerConfigs = streamsConfig.getRestoreConsumerConfigs(clientId);
        assertEquals("false", consumerConfigs.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
    }

    @Test
    public void testGetRestoreConsumerConfigsWithRestoreConsumerOverridenPrefix() {
        props.put(StreamsConfig.consumerPrefix(ConsumerConfig.MAX_POLL_RECORDS_CONFIG), "5");
        props.put(StreamsConfig.restoreConsumerPrefix(ConsumerConfig.MAX_POLL_RECORDS_CONFIG), "50");
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> returnedProps = streamsConfig.getRestoreConsumerConfigs(clientId);
        assertEquals("50", returnedProps.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG));
    }

    @Test
    public void testGetGlobalConsumerConfigs() {
        final Map<String, Object> returnedProps = streamsConfig.getGlobalConsumerConfigs(clientId);
        assertEquals(returnedProps.get(ConsumerConfig.CLIENT_ID_CONFIG), clientId + "-global-consumer");
        assertNull(returnedProps.get(ConsumerConfig.GROUP_ID_CONFIG));
    }

    @Test
    public void shouldSupportPrefixedGlobalConsumerConfigs() {
        props.put(consumerPrefix(ConsumerConfig.METRICS_NUM_SAMPLES_CONFIG), 1);
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> consumerConfigs = streamsConfig.getGlobalConsumerConfigs(clientId);
        assertEquals(1, consumerConfigs.get(ConsumerConfig.METRICS_NUM_SAMPLES_CONFIG));
    }

    @Test
    public void shouldSupportPrefixedPropertiesThatAreNotPartOfGlobalConsumerConfig() {
        props.put(consumerPrefix("interceptor.statsd.host"), "host");
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> consumerConfigs = streamsConfig.getGlobalConsumerConfigs(clientId);
        assertEquals("host", consumerConfigs.get("interceptor.statsd.host"));
    }

    @Test
    public void shouldBeSupportNonPrefixedGlobalConsumerConfigs() {
        props.put(ConsumerConfig.METRICS_NUM_SAMPLES_CONFIG, 1);
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> consumerConfigs = streamsConfig.getGlobalConsumerConfigs(groupId);
        assertEquals(1, consumerConfigs.get(ConsumerConfig.METRICS_NUM_SAMPLES_CONFIG));
    }

    @Test
    public void shouldResetToDefaultIfGlobalConsumerAutoCommitIsOverridden() {
        props.put(StreamsConfig.consumerPrefix(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG), "true");
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> consumerConfigs = streamsConfig.getGlobalConsumerConfigs(clientId);
        assertEquals("false", consumerConfigs.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
    }

    @Test
    public void testGetGlobalConsumerConfigsWithGlobalConsumerOverridenPrefix() {
        props.put(StreamsConfig.consumerPrefix(ConsumerConfig.MAX_POLL_RECORDS_CONFIG), "5");
        props.put(StreamsConfig.globalConsumerPrefix(ConsumerConfig.MAX_POLL_RECORDS_CONFIG), "50");
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> returnedProps = streamsConfig.getGlobalConsumerConfigs(clientId);
        assertEquals("50", returnedProps.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG));
    }

    @Test
    public void shouldSetInternalLeaveGroupOnCloseConfigToFalseInConsumer() {
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> consumerConfigs = streamsConfig.getMainConsumerConfigs(groupId, clientId, threadIdx);
        assertThat(consumerConfigs.get("internal.leave.group.on.close"), CoreMatchers.equalTo(false));
    }

    @Test
    public void shouldAcceptAtLeastOnce() {
        // don't use `StreamsConfig.AT_LEAST_ONCE` to actually do a useful test
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, "at_least_once");
        new StreamsConfig(props);
    }

    @Test
    public void shouldAcceptExactlyOnce() {
        // don't use `StreamsConfig.EXACLTY_ONCE` to actually do a useful test
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, "exactly_once");
        new StreamsConfig(props);
    }

    @Test(expected = ConfigException.class)
    public void shouldThrowExceptionIfNotAtLeastOnceOrExactlyOnce() {
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, "bad_value");
        new StreamsConfig(props);
    }

    @Test
    public void shouldAcceptBuiltInMetricsVersion0100To24() {
        // don't use `StreamsConfig.METRICS_0100_TO_24` to actually do a useful test
        props.put(StreamsConfig.BUILT_IN_METRICS_VERSION_CONFIG, "0.10.0-2.4");
        new StreamsConfig(props);
    }

    @Test
    public void shouldAcceptBuiltInMetricsLatestVersion() {
        // don't use `StreamsConfig.METRICS_LATEST` to actually do a useful test
        props.put(StreamsConfig.BUILT_IN_METRICS_VERSION_CONFIG, "latest");
        new StreamsConfig(props);
    }

    @Test
    public void shouldSetDefaultBuiltInMetricsVersionIfNoneIsSpecified() {
        final StreamsConfig config = new StreamsConfig(props);
        assertThat(config.getString(StreamsConfig.BUILT_IN_METRICS_VERSION_CONFIG), is(StreamsConfig.METRICS_LATEST));
    }

    @Test
    public void shouldThrowIfBuiltInMetricsVersionInvalid() {
        final String invalidVersion = "0.0.1";
        props.put(StreamsConfig.BUILT_IN_METRICS_VERSION_CONFIG, invalidVersion);
        final Exception exception = assertThrows(ConfigException.class, () -> new StreamsConfig(props));
        assertThat(
            exception.getMessage(),
            containsString("Invalid value " + invalidVersion + " for configuration built.in.metrics.version")
        );
    }

    @Test
    public void shouldResetToDefaultIfConsumerIsolationLevelIsOverriddenIfEosEnabled() {
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, EXACTLY_ONCE);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "anyValue");
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> consumerConfigs = streamsConfig.getMainConsumerConfigs(groupId, clientId, threadIdx);
        assertThat(consumerConfigs.get(ConsumerConfig.ISOLATION_LEVEL_CONFIG), equalTo(READ_COMMITTED.name().toLowerCase(Locale.ROOT)));
    }

    @Test
    public void shouldAllowSettingConsumerIsolationLevelIfEosDisabled() {
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, READ_UNCOMMITTED.name().toLowerCase(Locale.ROOT));
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> consumerConfigs = streamsConfig.getMainConsumerConfigs(groupId, clientId, threadIdx);
        assertThat(consumerConfigs.get(ConsumerConfig.ISOLATION_LEVEL_CONFIG), equalTo(READ_UNCOMMITTED.name().toLowerCase(Locale.ROOT)));
    }


    @Test
    public void shouldResetToDefaultIfProducerEnableIdempotenceIsOverriddenIfEosEnabled() {
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, EXACTLY_ONCE);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "anyValue");
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> producerConfigs = streamsConfig.getProducerConfigs(clientId);
        assertTrue((Boolean) producerConfigs.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
    }

    @Test
    public void shouldAllowSettingProducerEnableIdempotenceIfEosDisabled() {
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        final Map<String, Object> producerConfigs = streamsConfig.getProducerConfigs(clientId);
        assertThat(producerConfigs.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG), equalTo(false));
    }

    @Test
    public void shouldSetDifferentDefaultsIfEosEnabled() {
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, EXACTLY_ONCE);
        final StreamsConfig streamsConfig = new StreamsConfig(props);

        final Map<String, Object> consumerConfigs = streamsConfig.getMainConsumerConfigs(groupId, clientId, threadIdx);
        final Map<String, Object> producerConfigs = streamsConfig.getProducerConfigs(clientId);

        assertThat(consumerConfigs.get(ConsumerConfig.ISOLATION_LEVEL_CONFIG), equalTo(READ_COMMITTED.name().toLowerCase(Locale.ROOT)));
        assertTrue((Boolean) producerConfigs.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
        assertThat(producerConfigs.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG), equalTo(Integer.MAX_VALUE));
        assertThat(streamsConfig.getLong(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG), equalTo(100L));
    }

    @Test
    public void shouldNotOverrideUserConfigRetriesIfExactlyOnceEnabled() {
        final int numberOfRetries = 42;
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, EXACTLY_ONCE);
        props.put(ProducerConfig.RETRIES_CONFIG, numberOfRetries);
        final StreamsConfig streamsConfig = new StreamsConfig(props);

        final Map<String, Object> producerConfigs = streamsConfig.getProducerConfigs(clientId);

        assertThat(producerConfigs.get(ProducerConfig.RETRIES_CONFIG), equalTo(numberOfRetries));
    }

    @Test
    public void shouldNotOverrideUserConfigCommitIntervalMsIfExactlyOnceEnabled() {
        final long commitIntervalMs = 73L;
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, EXACTLY_ONCE);
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, commitIntervalMs);
        final StreamsConfig streamsConfig = new StreamsConfig(props);

        assertThat(streamsConfig.getLong(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG), equalTo(commitIntervalMs));
    }

    @Test
    public void shouldThrowExceptionIfCommitIntervalMsIsNegative() {
        final long commitIntervalMs = -1;
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, commitIntervalMs);
        try {
            new StreamsConfig(props);
            fail("Should throw ConfigException when commitIntervalMs is set to a negative value");
        } catch (final ConfigException e) {
            assertEquals("Invalid value -1 for configuration commit.interval.ms: Value must be at least 0", e.getMessage());
        }
    }

    @Test
    public void shouldUseNewConfigsWhenPresent() {
        final Properties props = getStreamsConfig();
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.Long().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.Long().getClass());
        props.put(StreamsConfig.DEFAULT_TIMESTAMP_EXTRACTOR_CLASS_CONFIG, MockTimestampExtractor.class);

        final StreamsConfig config = new StreamsConfig(props);
        assertTrue(config.defaultKeySerde() instanceof Serdes.LongSerde);
        assertTrue(config.defaultValueSerde() instanceof Serdes.LongSerde);
        assertTrue(config.defaultTimestampExtractor() instanceof MockTimestampExtractor);
    }

    @Test
    public void shouldUseCorrectDefaultsWhenNoneSpecified() {
        final StreamsConfig config = new StreamsConfig(getStreamsConfig());
        assertTrue(config.defaultKeySerde() instanceof Serdes.ByteArraySerde);
        assertTrue(config.defaultValueSerde() instanceof Serdes.ByteArraySerde);
        assertTrue(config.defaultTimestampExtractor() instanceof FailOnInvalidTimestamp);
    }

    @Test
    public void shouldSpecifyCorrectKeySerdeClassOnError() {
        final Properties props = getStreamsConfig();
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, MisconfiguredSerde.class);
        final StreamsConfig config = new StreamsConfig(props);
        try {
            config.defaultKeySerde();
            fail("Test should throw a StreamsException");
        } catch (final StreamsException e) {
            assertEquals("Failed to configure key serde class org.apache.kafka.streams.StreamsConfigTest$MisconfiguredSerde", e.getMessage());
        }
    }

    @Test
    public void shouldSpecifyCorrectValueSerdeClassOnError() {
        final Properties props = getStreamsConfig();
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, MisconfiguredSerde.class);
        final StreamsConfig config = new StreamsConfig(props);
        try {
            config.defaultValueSerde();
            fail("Test should throw a StreamsException");
        } catch (final StreamsException e) {
            assertEquals("Failed to configure value serde class org.apache.kafka.streams.StreamsConfigTest$MisconfiguredSerde", e.getMessage());
        }
    }

    @Test
    public void shouldThrowExceptionIfMaxInFlightRequestsGreaterThanFiveIfEosEnabled() {
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, EXACTLY_ONCE);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 7);
        final StreamsConfig streamsConfig = new StreamsConfig(props);
        try {
            streamsConfig.getProducerConfigs(clientId);
            fail("Should throw ConfigException when ESO is enabled and maxInFlight requests exceeds 5");
        } catch (final ConfigException e) {
            assertEquals("Invalid value 7 for configuration max.in.flight.requests.per.connection: Can't exceed 5 when exactly-once processing is enabled", e.getMessage());
        }
    }

    @Test
    public void shouldAllowToSpecifyMaxInFlightRequestsPerConnectionAsStringIfEosEnabled() {
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, EXACTLY_ONCE);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "3");

        new StreamsConfig(props).getProducerConfigs(clientId);
    }

    @Test
    public void shouldThrowConfigExceptionIfMaxInFlightRequestsPerConnectionIsInvalidStringIfEosEnabled() {
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, EXACTLY_ONCE);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "not-a-number");

        try {
            new StreamsConfig(props).getProducerConfigs(clientId);
            fail("Should throw ConfigException when EOS is enabled and maxInFlight cannot be paresed into an integer");
        } catch (final ConfigException e) {
            assertEquals("Invalid value not-a-number for configuration max.in.flight.requests.per.connection: String value could not be parsed as 32-bit integer", e.getMessage());
        }
    }

    @Test
    public void shouldSpecifyNoOptimizationWhenNotExplicitlyAddedToConfigs() {
        final String expectedOptimizeConfig = "none";
        final String actualOptimizedConifig = streamsConfig.getString(TOPOLOGY_OPTIMIZATION);
        assertEquals("Optimization should be \"none\"", expectedOptimizeConfig, actualOptimizedConifig);
    }

    @Test
    public void shouldSpecifyOptimizationWhenNotExplicitlyAddedToConfigs() {
        final String expectedOptimizeConfig = "all";
        props.put(TOPOLOGY_OPTIMIZATION, "all");
        final StreamsConfig config = new StreamsConfig(props);
        final String actualOptimizedConifig = config.getString(TOPOLOGY_OPTIMIZATION);
        assertEquals("Optimization should be \"all\"", expectedOptimizeConfig, actualOptimizedConifig);
    }

    @Test(expected = ConfigException.class)
    public void shouldThrowConfigExceptionWhenOptimizationConfigNotValueInRange() {
        props.put(TOPOLOGY_OPTIMIZATION, "maybe");
        new StreamsConfig(props);
    }

    @SuppressWarnings("deprecation")
    @Test
    public void shouldLogWarningWhenPartitionGrouperIsUsed() {
        props.put(StreamsConfig.PARTITION_GROUPER_CLASS_CONFIG, org.apache.kafka.streams.processor.DefaultPartitionGrouper.class);

        LogCaptureAppender.setClassLoggerToDebug(StreamsConfig.class);
        final LogCaptureAppender appender = LogCaptureAppender.createAndRegister();

        new StreamsConfig(props);

        LogCaptureAppender.unregister(appender);

        assertThat(
            appender.getMessages(),
            hasItem("Configuration parameter `" + StreamsConfig.PARTITION_GROUPER_CLASS_CONFIG + "` is deprecated and will be removed in 3.0.0 release."));
    }

    static class MisconfiguredSerde implements Serde {
        @Override
        public void configure(final Map configs, final boolean isKey) {
            throw new RuntimeException("boom");
        }

        @Override
        public Serializer serializer() {
            return null;
        }

        @Override
        public Deserializer deserializer() {
            return null;
        }
    }

    public static class MockTimestampExtractor implements TimestampExtractor {

        @Override
        public long extract(final ConsumerRecord<Object, Object> record, final long partitionTime) {
            return 0;
        }
    }
}
