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

import akka.actor.{Props, Actor}
import kamon.metrics._
import kamon.metrics.Subscriptions.TickMetricSnapshot
import kamon.metrics.ActorMetrics.ActorMetricSnapshot

class DatadogMetricTranslator extends Actor {
  val config = context.system.settings.config

  val metricKeyGenerator = new SimpleMetricKeyGenerator(config)
  val metricSender = context.actorOf(DatadogMetricsSender.props, "metrics-sender")

  def receive = {
    case TickMetricSnapshot(from, to, metrics) ⇒
      val translatedMetrics = metrics.collect {
        case (am@ActorMetrics(_), snapshot: ActorMetricSnapshot) ⇒ transformActorMetric(am, snapshot)
      }

      metricSender ! Datadog.MetricBatch(translatedMetrics.flatten)
  }

  def transformActorMetric(actorIdentity: ActorMetrics, snapshot: ActorMetricSnapshot): Vector[Datadog.Metric] = {
    val timeInMailboxKey = metricKeyGenerator.generateKey(actorIdentity, ActorMetrics.TimeInMailbox)
    val processingTimeKey = metricKeyGenerator.generateKey(actorIdentity, ActorMetrics.ProcessingTime)

    roll(timeInMailboxKey._1, timeInMailboxKey._2, snapshot.timeInMailbox, Datadog.Timing) ++
      roll(processingTimeKey._1, processingTimeKey._2, snapshot.processingTime, Datadog.Timing)
  }

  def roll(key: String, tags: List[String], snapshot: MetricSnapshotLike, metricBuilder: (String, List[String], Double, Double) ⇒ Datadog.Metric): Vector[Datadog.Metric] = {
    val builder = Vector.newBuilder[Datadog.Metric]
    for (measurement ← snapshot.measurements) {
      val samplingRate = 1D / measurement.count
      val scaledValue = Scale.convert(snapshot.scale, Scale.Milli, measurement.value)
      builder += metricBuilder.apply(key, tags, scaledValue, samplingRate)
    }

    builder.result()
  }

}

object DatadogMetricTranslator {
  def props: Props = Props[DatadogMetricTranslator]
}
