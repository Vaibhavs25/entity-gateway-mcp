-- ShedLock: guarantees a single reconciler instance runs across replicas.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until DATETIME2(3) NOT NULL,
    locked_at  DATETIME2(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
