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

import akka.actor.{ Props, Actor }
import akka.util.ByteString
import kamon.Kamon
import com.timgroup.statsd.NonBlockingStatsDClient
import kamon.datadog.Datadog.{ Timing, Gauge, Counter }

class DatadogMetricsSender extends Actor {

  import context.system

  val statsDExtension = Kamon(Datadog)
  val datadogAgent = new NonBlockingStatsDClient(statsDExtension.prefix, statsDExtension.hostname, statsDExtension.port)

  def receive = {
    case Datadog.MetricBatch(metrics) ⇒ sendMetricsToRemote(metrics, ByteString.empty)
  }

  final def sendMetricsToRemote(metrics: Iterable[Datadog.Metric], buffer: ByteString): Unit = {
    for (metric ← metrics) {
      metric match {
        case met: Counter ⇒ datadogAgent.count(metric.key, metric.value.toInt, metric.tags: _*)
        case met: Gauge   ⇒ datadogAgent.recordGaugeValue(metric.key, metric.value, metric.tags: _*)
        case met: Timing  ⇒ datadogAgent.recordExecutionTime(metric.key, metric.value.toLong, metric.tags: _*)
      }
    }
  }
}

object DatadogMetricsSender {
  def props: Props = Props[DatadogMetricsSender]
}