package com.owen282000.lifedashboard.harness

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A subscriber on the suite's broker (scripts/instrumented.sh forwards the device's
 * 127.0.0.1:1883 to it). Retained messages arrive first with the retain flag set; everything
 * a test asserts on after [drainRetained] is live, from the sync under test. A fresh probe
 * after a sync sees what the broker keeps.
 */
class MqttProbe(vararg filters: String) : Closeable {

    class Message(val topic: String, val payload: String, val retained: Boolean)

    private val client: Mqtt3AsyncClient = MqttClient.builder()
        .useMqttVersion3()
        .identifier("ldsuite-probe-" + UUID.randomUUID().toString().take(8))
        .serverHost(HOST)
        .serverPort(PORT)
        .buildAsync()

    private val received = CopyOnWriteArrayList<Message>()

    val messages: List<Message> get() = received.toList()

    init {
        client.connect().get(10, TimeUnit.SECONDS)
        filters.forEach { filter ->
            client.subscribeWith().topicFilter(filter).qos(MqttQos.AT_LEAST_ONCE)
                .callback { publish -> received += Message(publish.topic.toString(), String(publish.payloadAsBytes, Charsets.UTF_8), publish.isRetain) }
                .send().get(10, TimeUnit.SECONDS)
        }
    }

    /** Waits until the retained burst has stopped arriving (no message for [quietMs]). */
    fun drainRetained(quietMs: Long = 700): MqttProbe {
        var count = -1
        while (count != received.size) {
            count = received.size
            Thread.sleep(quietMs)
        }
        return this
    }

    /** Live messages on [topic], oldest first. */
    fun live(topic: String): List<Message> = received.filter { !it.retained && it.topic == topic }

    fun live(): List<Message> = received.filter { !it.retained }

    /** Waits until a live message on [topic] has arrived and returns the last one. */
    fun awaitLive(topic: String, timeoutMs: Long = 10_000): Message {
        Await.until("a live message on $topic", timeoutMs) { live(topic).isNotEmpty() }
        return live(topic).last()
    }

    /** The retained value on [topic] as a fresh subscriber sees it, or null when there is none. */
    fun retained(topic: String): Message? = received.lastOrNull { it.retained && it.topic == topic }

    override fun close() {
        runCatching { client.disconnect().get(5, TimeUnit.SECONDS) }
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 1883
    }
}
