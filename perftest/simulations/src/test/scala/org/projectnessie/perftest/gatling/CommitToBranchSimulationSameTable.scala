/*
 * Copyright (C) 2020 Dremio
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
package org.projectnessie.perftest.gatling

import io.gatling.core.Predef._
import io.gatling.core.scenario.Simulation
import io.gatling.core.structure.{ChainBuilder, ScenarioBuilder}
import org.projectnessie.client.api.NessieApiV1
import org.projectnessie.client.http.HttpClientBuilder
import org.projectnessie.error.NessieConflictException
import org.projectnessie.model.Operation.Put
import org.projectnessie.model._
import org.projectnessie.perftest.gatling.Predef.nessie

import scala.concurrent.duration.{FiniteDuration, HOURS, NANOSECONDS, SECONDS}

/** Gatling simulation to perform commits against Nessie. It commits the data to
  * the same table for all users. Has a bunch of configurables, see the `val`s
  * defined at the top of this class.
  */
class CommitToBranchSimulationSameTable extends Simulation {

  val params: CommitToBranchParams = CommitToBranchParams.fromSystemProperties()

  /** The actual benchmark code to measure Nessie-commit performance in various
    * scenarios.
    */
  private def commitToBranch: ChainBuilder = {
    val chain = exec(
      nessie("Commit")
        .execute { (client, session) =>
          // The commit number is the loop-variable declared buildScenario()
          val commitNum = session("commitNum").asOption[Int].get
          // Current Nessie Branch object
          val branch = session("branch").as[Branch]
          // Our "user ID", an integer supplied by Gatling
          val userId = session.userId
          // Table used in the Nessie commit
          val tableName = params.makeTableName(session)

          // Call the Nessie client operation to perform a commit
          val key = ContentKey.of("name", "space", tableName)
          val contentId = tableName + "_" + userId.toString

          val tableMeta = IcebergTable
            .of(
              s"path_on_disk_${tableName}_$commitNum",
              42,
              43,
              44,
              45,
              contentId
            )

          val existingTable =
            client.getContent.reference(branch).key(key).get().get(key)

          val op =
            if (commitNum > 0 && existingTable != null)
              Put.of(
                key,
                tableMeta,
                existingTable
              )
            else Put.of(key, tableMeta);

          val updatedBranch = client
            .commitMultipleOperations()
            .branch(branch)
            .commitMeta(
              CommitMeta.fromMessage(s"test-commit $userId $commitNum")
            )
            .operation(op)
            .commit()

          session.set("branch", updatedBranch)
        }
        .onException { (e, client, session) =>
          if (e.isInstanceOf[NessieConflictException]) {
            val branch = session("branch").as[Branch]
            session.set(
              "branch",
              client
                .getReference()
                .refName(branch.getName)
                .get()
                .asInstanceOf[Branch]
            )
          } else {
            session
          }
        }
    )

    if (params.opRate > 0) {
      // "pace" the commits, if commit-rate is configured
      val oneHour = FiniteDuration(1, HOURS)
      val nanosPerIteration =
        oneHour.toNanos / (params.opRate * oneHour.toSeconds)
      pace(FiniteDuration(nanosPerIteration.toLong, NANOSECONDS))
        .exitBlockOnFail(chain)
    } else {
      // if no commit-rate is configured, run "as fast as possible"
      chain
    }
  }

  /** Get the [[Branch]] object, create the branch in Nessie if needed.
    */
  private def getReference: ChainBuilder = {
    // If we don't have a reference for the branch yet, then try to create the branch and try to fetch the reference
    exec(
      nessie(s"Create branch $params.branch")
        .execute { (client, session) =>
          // create the branch (errors will be ignored)
          val branch = client
            .createReference()
            .reference(Branch.of(params.makeBranchName(session), null))
            .create()
            .asInstanceOf[Branch]
          session.set("branch", branch)
        }
        // ignore any exception, handled in the following `doIf()`
        .ignoreException()
        // don't measure/log this action
        .dontLog()
    ).doIf(session => !session.contains("branch")) {
      exec(
        nessie(s"Get reference $params.branch")
          .execute { (client, session) =>
            // retrieve the Nessie branch reference and store it in the Gatling session object
            val branch = client
              .getReference()
              .refName(params.makeBranchName(session))
              .get()
              .asInstanceOf[Branch]
            session.set("branch", branch)
          }
          // don't measure/log this action
          .dontLog()
      )
    }
  }

  private def buildScenario(): ScenarioBuilder = {
    val scn = scenario("Commit-To-Branch")
      .exec(getReference)

    if (params.numberOfCommits > 0) {
      // Process configured number of commits
      scn.repeat(params.numberOfCommits, "commitNum") {
        commitToBranch
      }
    } else {
      // otherwise run "forever" (or until "max-duration")
      scn.forever("commitNum") {
        commitToBranch
      }
    }
  }

  /** Sets up the simulation. Implemented as a function to respect the optional
    * maximum-duration.
    */
  private def doSetUp(): SetUp = {
    val nessieProtocol: NessieProtocol = nessie()
      .client(
        HttpClientBuilder
          .builder()
          .withUri(
            s"http://127.0.0.1:${System.getProperties.getProperty("quarkus.http.test-port")}/api/v1"
          )
          .fromSystemProperties()
          .build(classOf[NessieApiV1])
      )

    System.out.println(params.asPrintableString())

    var s: SetUp = setUp(buildScenario().inject(atOnceUsers(params.numUsers)))
    if (params.durationSeconds > 0) {
      s = s.maxDuration(FiniteDuration(params.durationSeconds, SECONDS))
    }
    s.protocols(nessieProtocol)
  }

  // This is where everything starts, doSetUp() returns the `SetUp` ...

  doSetUp()
}
