<div align="center">

# PokeMC

**A Minecraft genie powered by [Poke](https://poke.com).**
Players type a wish in chat — Poke decides what to do, does it on your server, and talks back in‑game.

![Minecraft](https://img.shields.io/badge/Minecraft-1.21-brightgreen)
![Java](https://img.shields.io/badge/Java-21-orange)
![Server](https://img.shields.io/badge/Server-Paper%20%2F%20Spigot-blue)
![Powered by Poke](https://img.shields.io/badge/Powered%20by-Poke-ff4f00)
![License](https://img.shields.io/badge/License-MIT-yellow)

[Features](#features) · [Setup](#setup-5-steps) · [Telegram](#telegram-optional) · [Commands](#commands) · [Troubleshooting](#troubleshooting)

</div>

---

## Features

- **Wish in chat** — a player types `poke give me a sword` and the genie answers.
- **Real AI brain** — your Poke account decides what to do, in its own voice.
- **Acts on the server** — gives items, runs commands, sets quests.
- **Safe by default** — a built‑in guard blocks op/ban/gamemode and other dangerous commands.
- **Stat‑verified quests** — rewards only unlock when the player actually does the thing.
- **Optional Telegram link** — talk to Poke through a normal Telegram chat instead.
- **One jar** — drop it in `plugins/`, everything else is bundled.

---

## How it works

```
Player types in chat:  "poke give me a netherite sword"
        |
        v
   PokeMC  -->  Poke (your AI)  -->  back into your server
                                      |- checks the player
                                      |- runs the command
                                      |- replies in-game:  Poke » Here you go!
```

Poke lives on the internet, so it needs a **public address** to reach your
server. That's the tunnel in Step 4 below. Don't worry — it's one command.

---

## Setup (5 steps)

> You need a **Paper or Spigot** Minecraft server (version **1.21**) and a
> **[Poke](https://poke.com) account**. Do the steps **in order**.

### 1. Install the plugin

Put `PokeMC-1.0.0.jar` into your server's `plugins/` folder. Start the server
once, then stop it. (This creates the config files.)

### 2. Turn on RCON

Open `server.properties` and set these three lines:

```properties
enable-rcon=true
rcon.port=25575
rcon.password=pick-a-password
```

### 3. Add your Poke API key

Get a key from Poke (Kitchen → API keys). Create a file at
`plugins/PokeMC/.env` and paste this in:

```bash
POKE_API_KEY=paste-your-key-here
```

> Keep this file private. Never share it or upload it anywhere.

### 4. Make your server reachable (the tunnel)

Poke needs to reach your server from the internet. Install
[cloudflared](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/)
and run **one** command:

```bash
cloudflared tunnel --url http://localhost:4053
```

It prints a URL like `https://something-random.trycloudflare.com`. **Copy it.**
Leave this running.

### 5. Connect it to Poke

Tell Poke about your server. Run this (replace the URL with yours):

```bash
npx poke@latest mcp add https://something-random.trycloudflare.com/sse -n minecraft
```

> The "secret key" Poke needs is auto‑generated and saved at
> `plugins/PokeMC/mcp-api-key.txt`. If the command asks for it, copy it from
> that file.

### Done

Start your server. In‑game, type:

```
poke say hello
```

If the genie replies, **it works.**

---

## Telegram (optional)

Want to talk to Poke through a real **Telegram** chat instead? You can. It links
**from the server console — no restarts, no file editing.**

**1.** Turn it on. In `plugins/PokeMC/.env` add:

```bash
TELEGRAM_ENABLED=true
```

**2.** Start the server, then type these in the **server console**, one at a time:

```
/poke link +15551234567     (your phone number)
/poke code 12345            (the code Telegram texts you)
/poke password yourpass     (ONLY if your Telegram has 2FA)
```

**3.** That's it. Check it with `/poke status`. You only ever do this **once** —
PokeMC remembers the login, so every restart after this just works.

---

## Commands

Type these in chat (as an admin) or the server console.

| Command | What it does |
| --- | --- |
| `/poke status` | Is the genie awake? Is Telegram linked? |
| `/poke retry` | Re‑test the connection to Poke |
| `/poke reload` | Reload the config |
| `/poke link <+phone>` | Start linking Telegram |
| `/poke code <code>` | Enter the Telegram login code |
| `/poke password <pw>` | Enter your Telegram 2FA password |

---

## Troubleshooting

| Problem | Fix |
| --- | --- |
| Genie won't reply in chat | Make sure Step 4 (tunnel) is still running and Step 5 (connect to Poke) was done. |
| "No Poke API key configured" | Re‑check Step 3. The key goes in `plugins/PokeMC/.env`. |
| Commands don't work | RCON isn't on. Re‑check Step 2 in `server.properties`. |
| Tunnel URL stopped working | Free cloudflared URLs change each run. Re‑run Step 4 and Step 5 with the new URL. |
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
- **[TDLib](https://core.telegram.org/tdlib)** — powers the optional Telegram bridge.

---

## License

[MIT](LICENSE) © cloudwithax
