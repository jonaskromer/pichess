package chess.persistence.postgres

import chess.opt.Optimisation

/** The `PG_POOL` arm of the performance experiment. `default` is the
  * HikariCP-backed pool (`PostgresDatabase.withSchemaLayerHikari`);
  * `baseline` is the original `Database.forURL` + `DriverDataSource`
  * path (`PostgresDatabase.withSchemaLayer`) that's been shipping.
  *
  * Flip with `PICHESS_OPT_PG_POOL=baseline make perf BACKENDS=postgres`
  * (or via the global `PICHESS_OPT_ALL=baseline` knob). See
  * `docs/perf-experiments.md` for the headline finding that motivated
  * this swap.
  */
object PostgresDatabaseOptimisations:

  given Optimisation[PostgresDatabase] with
    val name     = "PG_POOL"
    val default  = PostgresDatabase.withSchemaLayerHikari
    val baseline = PostgresDatabase.withSchemaLayer
