/**
 * The evaluator of queries (design D2 and D3 of {@code add-queries-and-aggregation}): it checks a query against the
 * schema of a view, reports every problem at once without reading anything, and then evaluates the steps over lists of
 * rows with the view's own reads. It works through the {@code View} interface only, so every view shares it. Not
 * exported; the entry point is {@link io.github.enricoag1982.cairn.core.query.QueryRunner}, which the views call.
 */
package io.github.enricoag1982.cairn.core.query;
