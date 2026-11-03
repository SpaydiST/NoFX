package dev.nofx.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import dev.nofx.NoFXConfig;
import dev.nofx.scan.FreecamScanner;
import dev.nofx.store.TrackingStore;
import dev.nofx.util.Msg;

import java.util.concurrent.CompletableFuture;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

public class NoFxCommand {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final NoFXConfig config;
    private final FreecamScanner scanner;
    private final TrackingStore tracking;

    public NoFxCommand(NoFXConfig config, FreecamScanner scanner, TrackingStore tracking) {
        this.config = config;
        this.scanner = scanner;
        this.tracking = tracking;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("nofx")
                .requires(stack -> stack.getSender().hasPermission("nofx.admin"))
                .executes(this::usage)
                .then(literal("reload").executes(this::reload))
                .then(literal("check")
                        .then(argument("target", StringArgumentType.word())
                                .suggests(suggestCheck)
                                .executes(this::check)))
                .then(literal("status")
                        .executes(this::statusAll)
                        .then(argument("target", StringArgumentType.word())
                                .suggests(suggestPlayer)
                                .executes(this::statusPlayer)))
                .then(literal("offenders").executes(this::offenders))
                .then(literal("unlock")
                        .then(argument("target", StringArgumentType.word())
                                .suggests(suggestPlayer)
                                .executes(this::unlock)))
                .then(literal("forget")
                        .then(argument("target", StringArgumentType.word())
                                .suggests(suggestPlayer)
                                .executes(this::forget)))
                .build();
    }

    private CommandSender sender(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender();
    }

    private int usage(CommandContext<CommandSourceStack> ctx) {
        sender(ctx).sendMessage(Msg.parse(config.msg("usage"), null));
        return 1;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        config.load();
        sender(ctx).sendMessage(Msg.parse(config.msg("reloaded"), null));
        return 1;
    }

    private int check(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        if (!config.scanEnabled()) {
            sender.sendMessage(Msg.parse(config.msg("scan-disabled"), null));
            return 0;
        }
        String targetName = ctx.getArgument("target", String.class);
        Player initiator = sender instanceof Player p ? p : null;
        if (targetName.equalsIgnoreCase("all")) {
            int count = 0;
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (target.hasPermission("nofx.bypass")) {
                    continue;
                }
                scanner.startScan(target, initiator, false);
                count++;
            }
            sender.sendMessage(Msg.parse(config.msg("scan-started"),
                    Map.of("player", count + " игроков")));
            return 1;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage(Msg.parse(config.msg("player-offline"), Map.of("player", targetName)));
            return 0;
        }
        scanner.startScan(target, initiator, false);
        return 1;
    }

    private int statusAll(CommandContext<CommandSourceStack> ctx) {
        int count = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (scanner.isScanning(online.getUniqueId())) {
                count++;
            }
        }
        sender(ctx).sendMessage(Msg.parse(config.msg("status-count"), Map.of("count", String.valueOf(count))));
        return 1;
    }

    private int statusPlayer(CommandContext<CommandSourceStack> ctx) {
        String targetName = ctx.getArgument("target", String.class);
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender(ctx).sendMessage(Msg.parse(config.msg("player-offline"), Map.of("player", targetName)));
            return 0;
        }
        boolean scanning = scanner.isScanning(target.getUniqueId());
        sender(ctx).sendMessage(Msg.parse(config.msg("status-scanning"),
                Map.of("player", target.getName(), "status", scanning ? "true" : "false")));
        return 1;
    }

    private int offenders(CommandContext<CommandSourceStack> ctx) {
        List<TrackingStore.TrackingEntry> entries = tracking.entries();
        if (entries.isEmpty()) {
            sender(ctx).sendMessage(Msg.parse(config.msg("offenders-empty"), null));
            return 1;
        }
        for (TrackingStore.TrackingEntry e : entries) {
            String last = Instant.ofEpochMilli(e.lastSeen())
                    .atZone(ZoneId.systemDefault()).format(TIME);
            sender(ctx).sendMessage(Msg.parse(config.msg("offenders-line"), Map.of(
                    "name", e.name(),
                    "detects", String.valueOf(e.detections()),
                    "protected", String.valueOf(e.protectedCount()),
                    "last", last)));
        }
        return 1;
    }

    private int unlock(CommandContext<CommandSourceStack> ctx) {
        String targetName = ctx.getArgument("target", String.class);
        UUID id = resolveUuid(targetName);
        if (id == null) {
            sender(ctx).sendMessage(Msg.parse(config.msg("player-offline"), Map.of("player", targetName)));
            return 0;
        }
        tracking.markClean(id);
        sender(ctx).sendMessage(Msg.parse(config.msg("unlocked"), Map.of("player", targetName)));
        return 1;
    }

    private int forget(CommandContext<CommandSourceStack> ctx) {
        String targetName = ctx.getArgument("target", String.class);
        UUID id = resolveUuid(targetName);
        if (id == null) {
            sender(ctx).sendMessage(Msg.parse(config.msg("player-offline"), Map.of("player", targetName)));
            return 0;
        }
        tracking.forget(id);
        sender(ctx).sendMessage(Msg.parse(config.msg("forgot"), Map.of("player", targetName)));
        return 1;
    }

    private UUID resolveUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline.hasPlayedBefore() || tracking.entries().stream()
                .anyMatch(e -> e.name().equalsIgnoreCase(name)) ? offline.getUniqueId() : null;
    }

    private final SuggestionProvider<CommandSourceStack> suggestCheck = (ctx, builder) ->
            suggestPlayers(builder, true);

    private final SuggestionProvider<CommandSourceStack> suggestPlayer = (ctx, builder) ->
            suggestPlayers(builder, false);

    private CompletableFuture<Suggestions> suggestPlayers(SuggestionsBuilder builder, boolean includeAll) {
        String input = builder.getRemaining().toLowerCase();
        if (includeAll && "all".startsWith(input)) {
            builder.suggest("all");
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase().startsWith(input)) {
                builder.suggest(p.getName());
            }
        }
        for (TrackingStore.TrackingEntry e : tracking.entries()) {
            if (e.name().toLowerCase().startsWith(input)) {
                builder.suggest(e.name());
            }
        }
        return builder.buildFuture();
    }
}
