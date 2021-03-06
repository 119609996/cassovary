/*
 * Copyright 2015 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.twitter.cassovary.algorithms.linkanalysis

import com.twitter.cassovary.graph.{DirectedGraph, Node}
import com.twitter.cassovary.util.Progress

/**
 * Parameters for PageRank
 * @param dampingFactor Probability of NOT randomly jumping to another node
 * @param maxIterations The maximum number of times that the link analysis algorithm will run before termination
 * @param tolerance The maximum error allowed.
 */
case class PageRankParams(dampingFactor: Double = 0.85,
                          maxIterations: Option[Int] = Some(10),
                          tolerance: Double = 1e-8)
  extends Params

/**
 * Class containing information to fully describe a page rank iteration.
 * @param pageRank The current set of pageRank values
 * @param error  The T1 error for the current iteration vs the previous iteration.
 */
case class PageRankIterationState(pageRank: Array[Double], error: Double, iteration: Int)
  extends IterationState

/**
 * PageRank is a link analysis algorithm designed to measure the importance of nodes in a graph.
 * Popularized by Google.
 *
 * Unoptimized for now, and runs in a single thread.
 */
class PageRank(graph: DirectedGraph[Node], params: PageRankParams = PageRankParams())
  extends LinkAnalysis[PageRankIterationState](graph, params, "pagerank") {

  private val outboundCount = new Array[Double](graph.maxNodeId + 1)

  graph foreach { node => efficientNeighbors(node) foreach { nbr => outboundCount(nbr) += 1} }

  lazy val dampingFactor = params.dampingFactor
  lazy val dampingAmount = (1.0D - dampingFactor) / graph.nodeCount

  protected def defaultInitialState: PageRankIterationState = {
    val initial = Array.tabulate(graph.maxNodeId + 1){ n => if (graph.existsNodeId(n)) 1.0 / graph.nodeCount else 0.0 }
    PageRankIterationState(initial, 100 + tolerance, 0)
  }

  def iterate(prevIteration: PageRankIterationState): PageRankIterationState = {
    val beforePR = prevIteration.pageRank
    val afterPR  = new Array[Double](graph.maxNodeId + 1)

    log.debug("Calculating new PageRank values based on previous iteration...")
    val prog = Progress("pagerank_calc", 65536, Some(graph.nodeCount))
    graph foreach { node =>
      val neighbors = efficientNeighbors(node)
      if (isInStored)
        afterPR(node.id) = neighbors.foldLeft(0.0){ (partialSum, nbr) => partialSum + beforePR(nbr) / outboundCount(nbr) }
      else {
        val givenPageRank = beforePR(node.id) / outboundCount(node.id)
        neighbors foreach { neighborId => afterPR(neighborId) += givenPageRank }
      }
      prog.inc
    }

    log.debug("Damping...")
    val progress_damp = Progress("pagerank_damp", 65536, Some(graph.nodeCount))
    if (dampingAmount > 0) {
      graph foreach { node =>
        afterPR(node.id) = dampingAmount + dampingFactor * afterPR(node.id)
        progress_damp.inc
      }
    }
    PageRankIterationState(afterPR, deltaOfArrays(prevIteration.pageRank, afterPR), prevIteration.iteration + 1)
  }
}
