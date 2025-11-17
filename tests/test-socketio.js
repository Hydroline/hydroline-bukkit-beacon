import io from "socket.io-client";
import fs from "node:fs";
import path from "node:path";
import dotenv from "dotenv";

dotenv.config();

function requireEnv(name) {
  const value = process.env[name];
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value;
}

async function main() {
  const host = process.env.BEACON_HOST || "127.0.0.1";
  const port = requireEnv("BEACON_PORT");
  const key = requireEnv("BEACON_KEY");
  const playerUuid = process.env.BEACON_PLAYER_UUID;
  const playerName = process.env.BEACON_PLAYER_NAME;
  const outDir =
    process.env.OUTPUT_DIR || path.resolve(process.cwd(), "output");
  await prepareOutputDir(outDir);

  const url = `http://${host}:${port}`;
  console.log(`Connecting to ${url} ...`);

  const socket = io(url, {
    transports: ["websocket"],
    reconnectionAttempts: 3,
    timeout: 10000,
  });

  socket.on("connect", async () => {
    console.log("Connected, socket id =", socket.id);

    try {
      const status = await emitWithAck(
        socket,
        "get_status",
        { key },
        "get_status"
      );
      await writeJson(outDir, "server_status.json", status);

      const serverTime = await emitWithAck(
        socket,
        "get_server_time",
        { key },
        "get_server_time"
      );
      await writeJson(outDir, "server_time.json", serverTime);

      const onlinePlayers = await emitWithAck(
        socket,
        "list_online_players",
        { key },
        "list_online_players"
      );
      await writeJson(outDir, "online_players.json", onlinePlayers);

      if (playerUuid || playerName) {
        const adv = await emitWithAck(
          socket,
          "get_player_advancements",
          { key, playerUuid, playerName },
          "get_player_advancements"
        );
        await writeJson(
          outDir,
          `advancements_${sanitize(
            playerUuid || playerName || "unknown"
          )}.json`,
          adv
        );

        const stats = await emitWithAck(
          socket,
          "get_player_stats",
          { key, playerUuid, playerName },
          "get_player_stats"
        );
        await writeJson(
          outDir,
          `stats_${sanitize(playerUuid || playerName || "unknown")}.json`,
          stats
        );

        const nbt = await emitWithAck(
          socket,
          "get_player_nbt",
          { key, playerUuid, playerName },
          "get_player_nbt"
        );
        await writeJson(
          outDir,
          `nbt_${sanitize(playerUuid || playerName || "unknown")}.json`,
          nbt
        );

        const identity = await emitWithAck(
          socket,
          "lookup_player_identity",
          { key, playerUuid, playerName },
          "lookup_player_identity"
        );
        await writeJson(
          outDir,
          `player_identity_${sanitize(
            playerUuid || playerName || "unknown"
          )}.json`,
          identity
        );
      } else {
        console.log(
          "BEACON_PLAYER_UUID/BEACON_PLAYER_NAME not set, skip player-specific queries."
        );
      }

      const force = await emitWithAck(
        socket,
        "force_update",
        { key },
        "force_update"
      );
      await writeJson(outDir, `force_update.json`, force);

      // MTR logs listing (first page)
      const mtrList = await emitWithAck(
        socket,
        "get_player_mtr_logs",
        {
          key,
          ...(playerUuid ? { playerUuid } : {}),
          ...(playerName ? { playerName } : {}),
          page: 1,
          pageSize: 20,
        },
        "get_player_mtr_logs"
      );
      await writeJson(outDir, `mtr_logs_page1.json`, mtrList);

      if (mtrList && mtrList.records && mtrList.records.length > 0) {
        const firstId = mtrList.records[0].id;
        const mtrDetail = await emitWithAck(
          socket,
          "get_mtr_log_detail",
          { key, id: firstId },
          "get_mtr_log_detail"
        );
        await writeJson(outDir, `mtr_log_${firstId}.json`, mtrDetail);
      }

      // MTR logs with singleDate = today
      const today = formatDate(new Date());
      const mtrToday = await emitWithAck(
        socket,
        "get_player_mtr_logs",
        {
          key,
          ...(playerUuid ? { playerUuid } : {}),
          ...(playerName ? { playerName } : {}),
          singleDate: today,
          page: 1,
          pageSize: 50,
        },
        "get_player_mtr_logs(singleDate)"
      );
      await writeJson(outDir, `mtr_logs_${today}.json`, mtrToday);

      // MTR logs with date range (last 7 days)
      const endDate = today;
      const startDate = formatDate(addDays(new Date(), -6));
      const mtrLast7 = await emitWithAck(
        socket,
        "get_player_mtr_logs",
        {
          key,
          ...(playerUuid ? { playerUuid } : {}),
          ...(playerName ? { playerName } : {}),
          startDate,
          endDate,
          page: 1,
          pageSize: 50,
        },
        "get_player_mtr_logs(range7d)"
      );
      await writeJson(
        outDir,
        `mtr_logs_${startDate}_to_${endDate}.json`,
        mtrLast7
      );

      // Player sessions: page 1
      const sessionsPage1 = await emitWithAck(
        socket,
        "get_player_sessions",
        {
          key,
          page: 1,
          pageSize: 50,
        },
        "get_player_sessions(page1)"
      );
      await writeJson(outDir, `player_sessions_page1.json`, sessionsPage1);

      // Player sessions: today only
      const sessionsToday = await emitWithAck(
        socket,
        "get_player_sessions",
        {
          key,
          singleDate: today,
          page: 1,
          pageSize: 100,
        },
        "get_player_sessions(today)"
      );
      await writeJson(outDir, `player_sessions_${today}.json`, sessionsToday);

      // Player sessions: JOIN only today
      const sessionsJoinToday = await emitWithAck(
        socket,
        "get_player_sessions",
        {
          key,
          eventType: "JOIN",
          singleDate: today,
          page: 1,
          pageSize: 100,
        },
        "get_player_sessions(join_today)"
      );
      await writeJson(
        outDir,
        `player_sessions_JOIN_${today}.json`,
        sessionsJoinToday
      );

      // Player sessions: by player if provided
      if (playerUuid || playerName) {
        const sessionsByPlayer = await emitWithAck(
          socket,
          "get_player_sessions",
          {
            key,
            ...(playerUuid ? { playerUuid } : {}),
            ...(playerName ? { playerName } : {}),
            page: 1,
            pageSize: 100,
          },
          "get_player_sessions(by_player)"
        );
        await writeJson(
          outDir,
          `player_sessions_${sanitize(
            playerUuid || playerName || "unknown"
          )}.json`,
          sessionsByPlayer
        );
      }
    } catch (err) {
      console.error("Test error:", err.message);
    } finally {
      socket.close();
    }
  });

  socket.on("connect_error", (err) => {
    console.error("Connect error:", err.message);
  });

  socket.on("error", (err) => {
    console.error("Socket error:", err);
  });
}

async function prepareOutputDir(dir) {
  if (fs.existsSync(dir)) {
    const entries = await fs.promises.readdir(dir);
    await Promise.all(
      entries.map((name) =>
        fs.promises.rm(path.join(dir, name), { recursive: true, force: true })
      )
    );
  } else {
    await fs.promises.mkdir(dir, { recursive: true });
  }
}

function sanitize(name) {
  return (name || "").replace(/[^a-zA-Z0-9_.-]/g, "_");
}

async function writeJson(outDir, fileName, data) {
  const target = path.join(outDir, fileName);
  const payload = {
    timestamp: new Date().toISOString(),
    data: data === undefined ? null : data,
  };
  await fs.promises.writeFile(
    target,
    JSON.stringify(payload, null, 2) + "\n",
    "utf8"
  );
  console.log(`Wrote ${target}`);
}

function emitWithAck(socket, event, payload, label) {
  return new Promise((resolve, reject) => {
    console.log(`\n>>> Emitting ${event} with payload:`, payload);
    const timer = setTimeout(() => {
      reject(new Error(`${label} ack timeout`));
    }, 10000);

    // socket.io v2 ack callback receives only the response args (no error-first)
    socket.emit(event, payload, (...args) => {
      clearTimeout(timer);
      if (args.length === 0) {
        console.log(`<<< [${label}] ACK response: <no-args>`);
        resolve(undefined);
        return;
      }
      console.log(`<<< [${label}] ACK response args:`, args);
      resolve(args[0]);
    });
  });
}

function formatDate(d) {
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  return `${yyyy}-${mm}-${dd}`;
}

function addDays(d, n) {
  const copy = new Date(d.getTime());
  copy.setDate(copy.getDate() + n);
  return copy;
}

main().catch((err) => {
  console.error("Fatal error:", err);
  process.exit(1);
});
