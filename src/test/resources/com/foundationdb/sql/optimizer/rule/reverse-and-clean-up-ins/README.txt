count-two-groups: Aggregate after two groups with IN.

exists: EXISTS to semi-join.

in-params: IN list with parameters.

in-subquery: IN SELECT to semi-join (not indexable).

in-subquery-outer-condition: IN SELECT to semi-join with condition referencing outside.

in-subquery-distinct: indexable IN with DISTINCT enabler.

in-subquery-not-distinct: indexable IN without DISTINCT enabler.

not-index: IN remains as semi-join.

two-groups: Product join with IN.

use-index: IN uses group index.

use-index-no-union: IN uses group index without union.

use-index-row: row value IN uses group index.

not-exists: NOT EXISTS to anti-join.

not-in: NOT IN to anti-join.

not-in-inner: NOT IN with join inside.

update-semi: UPDATE with semi-join.

map-join-project: Need both sides of outer loop.

map-join-project-nested: Needed in inner loop because outer loop is like that.

map-join-project-outer: Also need column from inside table feeding added Project.

nested-in: nested semi-joins

nested-not-in: nested anti-joins

nested-in-not: nested semi inside anti
