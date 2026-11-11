package org.pgledger.admin.console;

import java.util.ArrayList;
import java.util.List;
import org.pgledger.application.read.AttentionService;
import org.pgledger.application.read.AttentionService.AttentionQuery;
import org.pgledger.application.read.CatalogService.ProjectView;
import org.pgledger.application.read.Page;
import org.pgledger.application.read.StatsService;
import org.pgledger.application.read.StatsService.Stats;
import org.pgledger.application.read.StatsService.StatsQuery;
import org.pgledger.domain.rule.AttentionKind;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** What the overview shows (the page and the JSON of {@code /api/overview}): MCP status, counts, attention. */
@Component
public class OverviewService {

    private static final int SHOWN = 25;
    private static final int COUNTED = 1000;

    /** The number of items of one attention kind; {@code atLeast} when the count stopped at the largest page. */
    public record AttentionCount(AttentionKind kind, int count, boolean atLeast) {
    }

    public record Overview(McpStatus mcp, List<ProjectView> projects, String project, Stats stats,
            List<AttentionCount> attentionCounts, List<AttentionService.Item> attention, boolean moreAttention) {
    }

    private final McpHealthClient mcp;
    private final Choices choices;
    private final StatsService stats;
    private final AttentionService attention;

    public OverviewService(McpHealthClient mcp, Choices choices, StatsService stats, AttentionService attention) {
        this.mcp = mcp;
        this.choices = choices;
        this.stats = stats;
        this.attention = attention;
    }

    /** The overview of a project, or of the whole ledger when {@code project} is blank. */
    public Overview overview(String project) {
        String scope = StringUtils.hasText(project) ? project : null;
        List<AttentionCount> counts = new ArrayList<>();
        for (AttentionKind kind : AttentionKind.values()) {
            Page<AttentionService.Item> page = attention.attention(new AttentionQuery(scope, kind.name(), COUNTED, null));
            counts.add(new AttentionCount(kind, page.items().size(), page.truncated()));
        }
        Page<AttentionService.Item> first = attention.attention(new AttentionQuery(scope, null, SHOWN, null));
        return new Overview(mcp.status(), choices.project(null).projects(), scope,
                stats.stats(new StatsQuery(scope, null)), counts, first.items(), first.truncated());
    }
}
