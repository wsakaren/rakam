package org.rakam.report;

import com.facebook.presto.sql.ExpressionFormatter;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.QualifiedName;
import com.google.common.primitives.Ints;
import org.rakam.analysis.RetentionQueryExecutor;
import org.rakam.collection.event.metastore.Metastore;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalField;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.String.format;
import static org.rakam.util.ValidationUtil.checkTableColumn;

public abstract class AbstractRetentionQueryExecutor implements RetentionQueryExecutor {
    private final int MAXIMUM_LEAD = 15;
    private final QueryExecutor executor;
    private final Metastore metastore;

    public AbstractRetentionQueryExecutor(QueryExecutor executor, Metastore metastore) {
        this.executor = executor;
        this.metastore = metastore;
    }

    public abstract String convertTimestampFunction();
    public abstract String diffTimestamps();

    @Override
    public QueryExecution query(String project, String connectorField, Optional<RetentionAction> firstAction, Optional<RetentionAction> returningAction, DateUnit dateUnit, Optional<String> dimension, LocalDate startDate, LocalDate endDate) {
        String timeColumn;
        String timeTransformation;

        checkTableColumn(connectorField, "connector field");

        if(dateUnit == DateUnit.DAY) {
            long seconds = ChronoUnit.DAYS.getDuration().getSeconds();
            timeColumn = format("_time/%d", seconds);
            timeTransformation = format("cast("+convertTimestampFunction()+"((data.time)*%d) as date)", seconds);
        } else
        if(dateUnit == DateUnit.WEEK) {
            timeColumn = format("cast(date_trunc('week', "+convertTimestampFunction()+"(_time)) as date)");
            timeTransformation ="data.time";
        } else
        if(dateUnit == DateUnit.MONTH) {
            timeColumn = format("cast(date_trunc('month', "+convertTimestampFunction()+"(_time)) as date)");
            timeTransformation ="data.time";
        } else {
            throw new UnsupportedOperationException();
        }

        StringBuilder from = new StringBuilder();

        if(returningAction.isPresent()) {
            RetentionAction retentionAction = returningAction.get();
            from.append(generateQuery(project, retentionAction.collection(), connectorField, timeColumn, dimension,
                    retentionAction.filter(),
                    startDate, endDate));
        } else {
            Set<String> collectionNames = metastore.getCollectionNames(project);
            from.append(collectionNames.stream()
                    .map(collection -> generateQuery(project, collection, connectorField,
                            timeColumn, dimension, Optional.empty(), startDate, endDate))
                    .collect(Collectors.joining(" union all ")));
        }

        LocalDate start;
        LocalDate end;
        if(dateUnit == DateUnit.MONTH) {
            start = startDate.withDayOfMonth(1);
            end = endDate.withDayOfMonth(1).plus(1, ChronoUnit.MONTHS);
        }else
        if(dateUnit == DateUnit.WEEK) {
            TemporalField fieldUS = WeekFields.of(Locale.US).dayOfWeek();
            start = startDate.with(fieldUS, 1);
            end = endDate.with(fieldUS, 1).plus(1, ChronoUnit.MONTHS);
        } else
        if(dateUnit == DateUnit.DAY) {
            start = startDate;
            end = endDate;
        } else {
            throw new IllegalStateException();
        }
        int range = Math.min(MAXIMUM_LEAD, Ints.checkedCast(dateUnit.getTemporalUnit().between(start, end)));

        if (range < 0) {
            throw new IllegalArgumentException("startDate and endDate are invalid.");
        }

        String query;
        if(firstAction.isPresent()) {
            String timeSubtraction;
            if (dateUnit == DateUnit.DAY) {
                timeSubtraction = "returning_action.time - data.time";
            } else
            if (dateUnit == DateUnit.WEEK) {
                timeSubtraction = "(returning_action.time - data.time)/7";
            } else {
                timeSubtraction = String.format(diffTimestamps(), dateUnit.name().toLowerCase(), "data.time", "returning_action.time");
            }

            String firstActionQuery = format("%s group by 1, 2 %s",
                    generateQuery(project, firstAction.get().collection(), connectorField, timeColumn, dimension,
                            firstAction.get().filter(), startDate, endDate),
                    dimension.isPresent() ? ",3" : "");

            String dimensionColumn = dimension.isPresent() ? "data.dimension" : timeTransformation;

            query = format("with first_action as (\n" +
                            "  %s\n" +
                            "), \n" +
                            "returning_action as (\n" +
                            "  %s\n" +
                            ") \n" +
                            "select %s, cast(null as bigint) as lead, count(distinct %s) count from first_action data group by 1,2 union all\n" +
                            "select %s, %s, count(distinct data.%s) \n" +
                            "from first_action data join returning_action on (data.%s = returning_action.%s) \n" +
                            "where data.time < returning_action.time and %s < %d group by 1, 2",
                    firstActionQuery, from.toString(), dimensionColumn, connectorField,
                    dimensionColumn, timeSubtraction, connectorField, connectorField, connectorField,
                    timeSubtraction, MAXIMUM_LEAD);
        } else {
            String timeSubtraction;
            if (dateUnit == DateUnit.DAY) {
                timeSubtraction = "lead%d-time";
            }else
            if (dateUnit == DateUnit.WEEK) {
                timeSubtraction = "(lead%d-time) / 7";
            } else {
                timeSubtraction = String.format(diffTimestamps(), dateUnit.name().toLowerCase(), "time",  "lead%d");
            }

            String leadTemplate = "lead(time, %d) over "+String.format("(partition by %s order by %s, time)", connectorField, connectorField);
            String leadColumns = IntStream.range(0, range)
                    .mapToObj(i -> "(" + format(timeSubtraction, i) + ") as lead" + i)
                    .collect(Collectors.joining(", "));
            String groups = IntStream.range(2, range+2).mapToObj(Integer::toString).collect(Collectors.joining(", "));

            String leads = IntStream.range(0, range)
                    .mapToObj(i -> format(leadTemplate+" lead"+i, i))
                    .collect(Collectors.joining(", "));
            String leadColumnNames = IntStream.range(0, range).mapToObj(i -> "lead" + i).collect(Collectors.joining(", "));

            query = format("with daily_groups as (\n" +
                            "  select %s, time\n" +
                            "  from (%s) as data group by 1, 2\n" +
                            "), \n" +
                            "lead_relations as (\n" +
                            "  select %s, time, %s\n" +
                            "  from daily_groups\n" +
                            "),\n" +
                            "result as (\n" +
                            "   select %s as time, %s, count(distinct %s) as count\n" +
                            "   from lead_relations data group by 1, %s order by 1\n" +
                            ") \n" +
                            "select %s, cast(null as bigint) as lead, count(%s) as count from daily_groups data group by 1\n" +
                            "union all (select * from (select time, lead, count from result \n" +
                            "CROSS JOIN unnest(array[%s]) t(lead)) as data where lead < %d)",
                    connectorField, from.toString(), connectorField, leads, timeTransformation,
                    leadColumns, connectorField, groups, timeTransformation, connectorField,
                    leadColumnNames, MAXIMUM_LEAD);
        }

        return executor.executeRawQuery(query);
    }

    private String generateQuery(String project,
                                 String collection,
                                 String connectorField,
                                 String timeColumn,
                                 Optional<String> dimension,
                                 Optional<Expression> exp,
                                 LocalDate startDate,
                                 LocalDate endDate) {
        ZoneId utc = ZoneId.of("UTC");

        long startTs = startDate.atStartOfDay().atZone(utc).toEpochSecond();
        long endTs = endDate.atStartOfDay().atZone(utc).toEpochSecond();

        return format("select %s, %s as time %s from %s where _time between %d and %d %s",
                connectorField,
                timeColumn,
                dimension.isPresent() ? ", " + dimension.get() + " as dimension" : "",
                executor.formatTableReference(project, QualifiedName.of(collection)),
                startTs, endTs,
                exp.isPresent() ? "and " + exp.get().accept(new ExpressionFormatter.Formatter(), false) : "");
    }

}