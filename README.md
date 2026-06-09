<div align="center">

<img src="https://i.imgur.com/8sVzfth.png" width="128" alt="PokeMC logo" />

# PokeMC

**A Minecraft genie powered by [Poke](https://poke.com).**
Players type a wish in chat — Poke decides what to do, does it on your server, and talks back in‑game.

![Minecraft](https://img.shields.io/badge/Minecraft-1.21-brightgreen)
![Java](https://img.shields.io/badge/Java-21-orange)
![Server](https://img.shields.io/badge/Server-Paper%20%2F%20Spigot-blue)
![Powered by Poke](https://img.shields.io/badge/Powered%20by-Poke-ff4f00)
![License](https://img.shields.io/badge/License-MIT-yellow)

[Features](#features) · [Setup](#setup-5-steps) · [How players use it](#how-players-use-it) · [Commands](#commands) · [Troubleshooting](#troubleshooting)

</div>

---

## Features

- **Wish in chat** — a player types `poke give me a sword` and the genie answers.
- **Real AI brain** — your Poke account decides what to do, in its own voice.
- **Acts on the server** — gives items, runs commands, sets quests.
- **Safe by default** — a built‑in guard blocks op/ban/gamemode and other dangerous commands.
- **Stat‑verified quests** — rewards only unlock when the player actually does the thing.
- **One at a time, no chaos** — when several players wish at once, they're served in
  order, each shown a private boss‑bar progress throbber while they wait.

---

## How it works

```
Player: "poke give me a netherite sword"
   |
   v
PokeMC  -->  Poke (your AI), reached over a Telegram chat
   |              |
   |              |- acts on your server through PokeMC's tools (the tunnel)
   |              |- writes its reply back in the Telegram chat
   v              v
Poke » Here you go!   (shown in Minecraft chat)
```

Two connections make this work, and you set up both below:

1. **A tunnel** — so Poke can reach your server to *do* things (give items, run commands).
2. **Telegram** — so Poke can *talk back*. (Poke's plain API can act but doesn't reliably
   send replies, so PokeMC routes the conversation through a Telegram chat. This is required.)

Don't worry — both are just a few commands.

---

## Setup (5 steps)

> You need a **Paper or Spigot** Minecraft server (version **1.21**), a
> **[Poke](https://poke.com) account**, and a **Telegram account**. Do the steps
> **in order**.

### 1. Install the plugin

Put `PokeMC-1.0.0.jar` into your server's `plugins/` folder. Start the server
once, then stop it. (This creates the config files.)

### 2. Turn on RCON

This is how Poke runs commands. Open `server.properties` and set these three lines:

```properties
enable-rcon=true
rcon.port=25575
rcon.password=pick-a-password
```

### 3. Open a tunnel

This gives your server a public address so Poke can reach it. Install
[cloudflared](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/)
and run **one** command:

```bash
cloudflared tunnel --url http://localhost:4053
```

It prints a URL like `https://something-random.trycloudflare.com`. **Copy it.**
Leave this running.

### 4. Connect the tunnel to Poke

Tell Poke where your server is (use **your** URL, and keep the `/sse` on the end):

```bash
npx poke@latest mcp add https://something-random.trycloudflare.com/sse -n minecraft
```

> If it asks for a secret key, it's auto‑generated and saved at
> `plugins/PokeMC/mcp-api-key.txt` — copy it from there.

### 5. Link Telegram

This is how Poke talks back. In `plugins/PokeMC/.env`, add:

```bash
TELEGRAM_ENABLED=true
```

Start the server, then type these in the **server console**, one at a time:

```
/poke link +15551234567     (your phone number)
/poke code 12345            (the code Telegram texts you)
/poke password yourpass     (ONLY if your Telegram has 2FA)
```

You only ever do this **once** — PokeMC remembers the login, so every restart
after this just works. Check it anytime with `/poke status`.

### Done

In‑game, type:

```
poke say hello
```

If the genie replies, **it works.**

---

## How players use it

Anyone just types `poke` and what they want in chat:

```
poke give me a stack of diamonds
poke build me a quest to earn a sword
poke what do you think of me
```

If several players wish at the same time, the genie handles them **one at a
time**. Anyone waiting sees a small boss bar at the top of their screen (only
they can see it) that spins while they wait their turn, then while their wish is
being granted. It clears when the genie replies.

---

## Commands

Type these in chat (as an admin) or the server console.

| Command | What it does |
| --- | --- |
| `/poke status` | Is the genie awake? Is Telegram linked? Who's in line? |
| `/poke retry` | Re‑test the connection to Poke |
| `/poke reload` | Reload the config |
| `/poke link <+phone>` | Start linking Telegram |
| `/poke code <code>` | Enter the Telegram login code |
| `/poke password <pw>` | Enter your Telegram 2FA password |

---

## Troubleshooting

| Problem | Fix |
| --- | --- |
| Plugin disabled itself on start | Telegram is required. Set `TELEGRAM_ENABLED=true` in `plugins/PokeMC/.env` and restart, then link with `/poke link`. |
| Genie won't act (no items/commands) | The tunnel (Step 3) or the connect step (Step 4) isn't set up. Re‑check both. |
| Genie acts but never replies | Telegram isn't linked. Run `/poke status`, then the command it asks for. |
| Commands don't work at all | RCON isn't on. Re‑check Step 2 in `server.properties`. |
| Tunnel URL stopped working | Free cloudflared URLs change each run. Re‑run Step 3 and Step 4 with the new URL. |
| Telegram won't link | Run `/poke status` to see what it's waiting for, then run the matching command. |
| Want to check the tunnel | Open `https://<your-url>/health` in a browser — it should say **alive**. |

---

## Building from source

Needs Java 21 and Maven.

```bash
mvn -DskipTests package
# -> target/PokeMC-1.0.0.jar
```

---

## Credits

- **[Poke](https://poke.com)** — the AI brain.
- **Clanker** — the project PokeMC is forked from.
- **[TDLib](https://core.telegram.org/tdlib)** — powers the Telegram bridge.

---

## License

[MIT](LICENSE) © cloudwithax
