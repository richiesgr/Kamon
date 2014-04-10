/*
 * =========================================================================================
 * Copyright © 2013-2014 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.datadog

import akka.actor._
import kamon.Kamon
import kamon.metrics._
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import com.typesafe.config.Config

object Datadog extends ExtensionId[DatadogExtension] with ExtensionIdProvider {
  override def lookup(): ExtensionId[_ <: Extension] = Datadog

  override def createExtension(system: ExtendedActorSystem): DatadogExtension = new DatadogExtension(system)

  trait MetricKeyGenerator {
    def generateKey(groupIdentity: MetricGroupIdentity, metricIdentity: MetricIdentity): (String, List[String])
  }

  sealed trait Metric {
    def key: String

    def value: Double

    def tags: List[String]

    def suffix: String

    def samplingRate: Double
  }

  case class Counter(key: String, tags: List[String], value: Double = 1D, samplingRate: Double = 1.0) extends Metric {
    val suffix: String = "c"
  }

  case class Timing(key: String, tags: List[String], value: Double, samplingRate: Double = 1.0) extends Metric {
    val suffix: String = "ms"
  }

  case class Gauge(key: String, tags: List[String], value: Double, samplingRate: Double = 1.0) extends Metric {
    val suffix: String = "g"
  }

  case class MetricBatch(metrics: Iterable[Metric])

}

class DatadogExtension(system: ExtendedActorSystem) extends Kamon.Extension {
  private val statsDConfig = system.settings.config.getConfig("kamon.datadog")

  val prefix = statsDConfig.getString("simple-metric-key-generator.prefix")
  val hostname = statsDConfig.getString("hostname")
  val port = statsDConfig.getInt("port")
  val flushInterval = statsDConfig.getMilliseconds("flush-interval")
  val maxPacketSize = statsDConfig.getInt("max-packet-size")
  val tickInterval = system.settings.config.getMilliseconds("kamon.metrics.tick-interval")

  val statsDMetricsListener = buildMetricsListener(tickInterval, flushInterval)

  val includedActors = statsDConfig.getStringList("includes.actor").asScala
  for (actorPathPattern ← includedActors) {
    Kamon(Metrics)(system).subscribe(ActorMetrics, actorPathPattern, statsDMetricsListener, permanently = true)
  }

  def buildMetricsListener(tickInterval: Long, flushInterval: Long): ActorRef = {
    assert(flushInterval >= tickInterval, "StatsD flush-interval needs to be equal or greater to the tick-interval")

    val metricsTranslator = system.actorOf(DatadogMetricTranslator.props, "statsd-metrics-translator")
    if (flushInterval == tickInterval) {
      // No need to buffer the metrics, let's go straight to the metrics translator.
      metricsTranslator
    } else {
      system.actorOf(TickMetricSnapshotBuffer.props(flushInterval.toInt.millis, metricsTranslator), "statsd-metrics-buffer")
    }
  }
}

class SimpleMetricKeyGenerator(config: Config) extends Datadog.MetricKeyGenerator {

  def generateKey(groupIdentity: MetricGroupIdentity, metricIdentity: MetricIdentity): (String, List[String]) = {
    val splitName = groupIdentity.name.split("/").reverse

    val actorName = splitName.length match {
      case 0 ⇒ None
      case 1 ⇒ Some(splitName.head)
      case _ ⇒ Some(if (splitName.head.charAt(0) != '$') splitName.head else splitName(1))
    }

    val tags = actorName match {
      case Some(n) ⇒ List[String](s"actor:$n")
      case None    ⇒ List[String]()
    }

    (metricIdentity.name, tags)
  }
}

