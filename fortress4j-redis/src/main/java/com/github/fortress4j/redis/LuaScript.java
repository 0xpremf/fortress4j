package com.github.fortress4j.redis;

import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class LuaScript {

    private final String source;
    private volatile String sha;

    private LuaScript(String source) {
        this.source = source;
    }

    static LuaScript fromResource(String path) {
        try (InputStream in = LuaScript.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing script: " + path);
            return new LuaScript(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    List<Object> run(RedisCommands<String, String> commands, String[] keys, String[] args) {
        String digest = sha;
        if (digest == null) {
            digest = sha = commands.scriptLoad(source);
        }
        try {
            return commands.evalsha(digest, ScriptOutputType.MULTI, keys, args);
        } catch (RedisNoScriptException e) {
            // Redis forgot the script (restart, failover, SCRIPT FLUSH): reload once and retry.
            digest = sha = commands.scriptLoad(source);
            return commands.evalsha(digest, ScriptOutputType.MULTI, keys, args);
        }
    }
}