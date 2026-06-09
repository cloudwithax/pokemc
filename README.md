<div align="center">

<img src="https://i.imgur.com/8sVzfth.png" width="128" alt="PokeMC logo" />

# PokeMC

**Your favorite AI assistant, now in Minecraft.**

![Minecraft](https://img.shields.io/badge/Minecraft-1.21-brightgreen)
![Java](https://img.shields.io/badge/Java-21-orange)
![Server](https://img.shields.io/badge/Server-Paper%20%2F%20Spigot-blue)
![Powered by Poke](https://img.shields.io/badge/Powered%20by-Poke-ff4f00)
![License](https://img.shields.io/badge/License-MIT-yellow)

[Features](#features) · [Setup](#setup-5-steps) · [How to use it](#how-to-use-it) · [Commands](#commands) · [Troubleshooting](#troubleshooting)

</div>

---

## Features

- **In-game chat responses**: a player types `poke give me a sword` and Poke answers.
- **RCON integration**: Poke can give items, run commands and set quests using RCON.
- **Safe by default**: A built‑in guard blocks op/ban/gamemode and other dangerous commands.
- **Stat‑verified quests**: Poke rewards players with requested items by checking in-game stats in real time for a better experience
- **Memory**: Poke remembers things about each and every single player, responses are tailored per user
- **Native Telegram Client**: A custom-built Telegram client is included so Poke can send/receive messages in chat.



## Setup (5 steps)

> You need a **Paper or Spigot** Minecraft server (version **1.21**), a
> **[Poke](https://poke.com) account**, and a **Telegram account**. Do the steps
> **in order**.


### 1. Turn on RCON

This is how Poke will run commands. Open `server.properties` and set these three lines:

```properties
enable-rcon=true
rcon.port=25575
rcon.password=pick-a-password
```

The Poke plugin will infer it automatically. You may also set it in config.yml if you wish.

### 2. Install the plugin

[Download the jar from releases](https://github.com/cloudwithax/pokemc/releases/latest) and put it in your servers `plugins/` folder. Restart/start the server once its in.


### 3. Set up a public endpoint

The PokeMC plugin exposes a MCP server on port **4053** for calling tools that needs to be reached publicly. This is **required** for functionality.

We recommend setting up a reverse proxy or a Cloudflare/ngrok tunnel to achieve this.

#### Cloudflared:

```bash
cloudflared tunnel create <TUNNEL-NAME>

cloudflared tunnel route dns <TUNNEL-NAME> <://domain.com>

cloudflared tunnel run --url http://<server ip>:4053 <TUNNEL-NAME>
```

#### ngrok:

```bash
ngrok http 4053
```

(First time only: run `ngrok config add-authtoken <YOUR-TOKEN>`; grab the token
from your ngrok dashboard.) The public URL is printed in the terminal; use it in Step 4.

#### Caddy:

Add to your `Caddyfile`. `flush_interval -1` disables buffering so SSE streams
through in real time:

```caddyfile
your-domain.com {
    reverse_proxy localhost:4053 {
        flush_interval -1
    }
}
```

Then `caddy reload`. Caddy provisions HTTPS automatically.

#### nginx:

SSE needs buffering off and a long read timeout, otherwise replies stall and the
connection dies after 60s. Add an `server` block (or merge into your existing one):

```nginx
server {
    listen 443 ssl;
    server_name your-domain.com;

    # ssl_certificate / ssl_certificate_key here

    location / {
        proxy_pass http://127.0.0.1:4053;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Connection "";

        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 86400s;
    }
}
```

Then `nginx -t && nginx -s reload`.

#### Apache:

Enable `mod_proxy` and `mod_proxy_http` (`a2enmod proxy proxy_http` on
Debian/Ubuntu). `flushpackets=on` is the SSE-critical bit; without it Apache
buffers events:

```apache
<VirtualHost *:443>
    ServerName your-domain.com

    # SSLEngine on + cert paths here

    ProxyPreserveHost On
    ProxyPass        / http://127.0.0.1:4053/ flushpackets=on timeout=86400
    ProxyPassReverse / http://127.0.0.1:4053/
</VirtualHost>
```

Then `apachectl configtest && apachectl graceful`.




### 4. Connect the tunnel to Poke

Add your server as an MCP integration in Poke:
**<https://poke.com/settings/connections/integrations/new>**

Fill it in like this:

- **URL**: your tunnel URL, e.g.
  `https://something-random.trycloudflare.com/sse`
- **API key**: paste PokeMC's MCP key here. On first start it's generated into
  `plugins/PokeMC/config.yml` (`mcp.api-key`) and printed in the server console;
  copy it from either place.

> ⚠️ **IMPORTANT**
> 1. The URL **must end in `/sse`**. Without it, Poke connects but sees **no tools**.
> 2. You **must** fill in the **API key**, even though the website marks it
>    *optional*. Leaving it blank breaks the connection.

### 5. Link Telegram

In order for Poke to be able to communicate with your server, you MUST link your Telegram account to the plugin.

Ensure you have already opened a channel with `@interaction_poke_bot`

After that, link your account

```sh
poke link +15551234567 # (must match E.164 format)
```

This will send a auth code to your account, fill it using:

```sh
poke code <code>
```

**Optional:** if your account has 2FA, you will also be asked to provide a password:

```sh
poke password <password>
```

After that your account will be linked in the plugin. It will automatically run a health check to ensure it can contact the MCP. If you HAVENT set up your MCP server and linked it to Poke, it will NOT WORK.


In‑game, type:

```
poke say hello
```

If Poke replies, you have successfully set up the integration and no further action is required.

---

## How to use it

To talk with poke, prefix your message with `poke` and your request:

```
poke give me a stack of diamonds
poke build me a quest to earn a sword
poke what do you think of me
```

Poke should reply to the chat globally and will mention the player. 

If several players send requests at the same time, Poke handles them **one at a
time**.  The player will see a status bar in-game while the request is queued.

---

## Commands


| Command | What it does |
| --- | --- |
| `/poke status` | Is Poke awake? Is Telegram linked? Who's in line? |
| `/poke retry` | Re‑test the connection to Poke |
| `/poke reload` | Reload the config |
| `/poke link <+phone>` | Start linking Telegram |
| `/poke code <code>` | Enter the Telegram login code |
| `/poke password <pw>` | Enter your Telegram 2FA password |

---

## Troubleshooting

| Problem | Fix |
| --- | --- |
| Plugin disabled itself on start | Telegram is required. Set `telegram.enabled: true` in `plugins/PokeMC/config.yml` and restart, then link with `/poke link`. |
| Poke won't act (no items/commands) | The tunnel either cannot be communicated to ([Step 3](#3-set-up-a-public-endpoint)) or the tunnel was not connected to Poke ([Step 4](#4-connect-the-tunnel-to-poke)) Re‑check both. |
| Poke acts but never replies | Telegram isn't linked. Run `/poke status`, then the command it asks for. |
| Commands don't work at all | RCON isn't on. Re‑check [Step 1](#1-turn-on-rcon).  |
| Tunnel URL stopped working | Please check that your tunnel was setup properly in [Step 3](#3-set-up-a-public-endpoint) and that its reachable |
| Telegram won't link | Run `/poke status` to see what it's waiting for, then run the matching command. |
| Want to check the tunnel | Open `https://<your-url>/health` in a browser; it should say **alive**. |



## Building from source

You will need Java 21 and Maven to build the plugin.

```bash
mvn -DskipTests package
# -> target/PokeMC-1.0.0.jar
```

---

## Credits

- **[Poke](https://poke.com)**: the AI brain.
- **[Clanker](https://github.com/cloudwithax/clanker-mc)**: the project PokeMC is forked from.
- **[TDLib](https://core.telegram.org/tdlib)**: powers the Telegram bridge.

---

## License

[MIT](LICENSE) © cloudwithax
