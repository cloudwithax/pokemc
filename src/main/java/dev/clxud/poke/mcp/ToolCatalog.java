package dev.clxud.poke.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * The MCP tool definitions Poke sees on {@code tools/list}. These mirror the
 * actions Clanker exposed to its model, plus two Poke-specific tools:
 * {@code reply_to_player} (how Poke's voice reaches in-game chat) and
 * {@code confirm_tools} (the startup self-test handshake). Note MCP uses
 * {@code inputSchema}, not the OpenAI {@code parameters} key.
 */
final class ToolCatalog {

    private ToolCatalog() {
    }

    static JsonArray tools() {
        JsonArray tools = new JsonArray();

        JsonObject runProps = new JsonObject();
        runProps.add("player", str("Exact Minecraft username of the wishing player."));
        runProps.add("command", str("A single Minecraft command to run on the server console, without a "
                + "leading slash. Target the player by their exact name."));
        tools.add(tool("run_command",
                "Run ONE Minecraft command via RCON to grant (or technically grant) a wish. Returns the "
                        + "server's response. A hard safety guard blocks op/ban/gamemode/admin-items/over-cap "
                        + "enchants/lag-bombs and returns a reason if it refuses.",
                runProps, "player", "command"));

        JsonObject aspect = str("What to audit: inventory | health | xp | position | item_count.");
        aspect.add("enum", arr("inventory", "health", "xp", "position", "item_count"));
        JsonObject inspectProps = new JsonObject();
        inspectProps.add("player", str("Exact Minecraft username to audit."));
        inspectProps.add("aspect", aspect);
        inspectProps.add("item", str("Item id to count (only when aspect=item_count), e.g. diamond."));
        tools.add(tool("inspect_player",
                "Read-only audit of a player's current state, to catch liars and cheaters before granting.",
                inspectProps, "player", "aspect"));

        JsonObject statCat = str("Stat category: custom | killed | killed_by | mined | used | crafted | "
                + "broken | picked_up | dropped.");
        statCat.add("enum", arr("custom", "killed", "killed_by", "mined", "used", "crafted",
                "broken", "picked_up", "dropped"));
        JsonObject statProps = new JsonObject();
        statProps.add("player", str("Exact Minecraft username."));
        statProps.add("category", statCat);
        statProps.add("key", str("Stat key, e.g. minecraft:zombie, minecraft:jump, minecraft:stone."));
        tools.add(tool("read_stat",
                "Read one of a player's real Minecraft statistics (current cumulative value). Use it to scope "
                        + "a quest and to know their starting point.",
                statProps, "player", "category", "key"));

        JsonObject questCat = str("Stat category that proves completion: custom | killed | mined | used | "
                + "crafted | broken | picked_up | dropped | killed_by.");
        questCat.add("enum", arr("custom", "killed", "killed_by", "mined", "used", "crafted",
                "broken", "picked_up", "dropped"));
        JsonObject amount = new JsonObject();
        amount.addProperty("type", "integer");
        amount.addProperty("description", "How much that stat must INCREASE from now for the quest to count.");
        JsonObject rewards = new JsonObject();
        rewards.addProperty("type", "array");
        rewards.add("items", str("A command to run as reward on completion (no leading slash). Must be legal."));
        rewards.addProperty("description", "Commands to run when they complete the quest.");
        JsonObject questProps = new JsonObject();
        questProps.add("player", str("Exact Minecraft username."));
        questProps.add("description", str("The task you set the player, in your own voice (shown to them). "
                + "Make it as trivial or absurd as you please."));
        questProps.add("category", questCat);
        questProps.add("key", str("Stat key to track, e.g. minecraft:zombie (killed), minecraft:jump (custom), "
                + "minecraft:walk_one_cm (custom; distances are in centimetres, so 1 block = 100)."));
        questProps.add("amount", amount);
        questProps.add("reward_commands", rewards);
        tools.add(tool("assign_quest",
                "Set the player a task they must DO in-game, verified by their statistics, before earning a "
                        + "reward. Snapshots their current stat as the baseline. Tell them the task afterwards via "
                        + "reply_to_player. Replaces any existing quest.",
                questProps, "player", "description", "category", "key", "amount", "reward_commands"));

        JsonObject completeProps = new JsonObject();
        completeProps.add("player", str("Exact Minecraft username."));
        tools.add(tool("complete_quest",
                "Verify the player's active quest against their real statistics and, ONLY if complete, run the "
                        + "promised reward commands. This is the ONLY way a quest reward is paid out.",
                completeProps, "player"));

        JsonObject rememberProps = new JsonObject();
        rememberProps.add("player", str("Exact Minecraft username."));
        rememberProps.add("note", str("A short durable note about this player for future wishes."));
        tools.add(tool("remember",
                "Save a short note about this player to your permanent memory.",
                rememberProps, "player", "note"));

        JsonObject replyProps = new JsonObject();
        replyProps.add("player", str("Exact Minecraft username to speak to."));
        replyProps.add("message", str("Your spoken reply, shown in their Minecraft chat. PLAIN TEXT ONLY: no "
                + "markdown, emoji, or special symbols — Minecraft chat cannot render them."));
        tools.add(tool("reply_to_player",
                "Deliver your final spoken reply to the player in-game. REQUIRED: call this exactly once, last, "
                        + "after any other tools, to finish handling a wish.",
                replyProps, "player", "message"));

        JsonObject confirmProps = new JsonObject();
        JsonObject toolsArr = new JsonObject();
        toolsArr.addProperty("type", "array");
        toolsArr.add("items", str("A tool name you can see."));
        toolsArr.addProperty("description", "Names of the Minecraft tools currently visible to you.");
        confirmProps.add("tools", toolsArr);
        tools.add(tool("confirm_tools",
                "Startup self-test handshake. When asked, call this once with the list of tool names you can "
                        + "see, to confirm the connection between Poke and the Minecraft server is live.",
                confirmProps, "tools"));

        return tools;
    }

    // ---- schema builders ---------------------------------------------------

    private static JsonObject tool(String name, String description, JsonObject properties, String... required) {
        JsonArray req = new JsonArray();
        for (String r : required) {
            req.add(r);
        }
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", req);

        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        tool.add("inputSchema", schema);
        return tool;
    }

    private static JsonObject str(String description) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "string");
        p.addProperty("description", description);
        return p;
    }

    private static JsonArray arr(String... values) {
        JsonArray a = new JsonArray();
        for (String v : values) {
            a.add(v);
        }
        return a;
    }
}
