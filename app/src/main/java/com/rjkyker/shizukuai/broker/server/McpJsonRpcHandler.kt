package com.rjkyker.shizukuai.broker.server

import android.util.Log
import com.rjkyker.shizukuai.broker.mcp.McpToolExecutor
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * MCP (Model Context Protocol) JSON-RPC 2.0 handler shared by POST /mcp and the /ws WebSocket.
 * Methods: initialize, notifications/initialized, ping, tools/list, tools/call.
 * Auth is enforced by BrokerServer before this is reached.
 */
class McpJsonRpcHandler(private val tools: McpToolExecutor) {

    /**
     * Handles one JSON-RPC message or a batch. Returns the serialized response, or null when the
     * input contained only notifications (HTTP layer answers 202 Accepted with no body).
     */
    fun handle(raw: String, confirmHeader: Boolean = false): String? {
        val parsed = try {
            JSONTokener(raw.trim()).nextValue()
        } catch (e: Exception) {
            return error(JSONObject.NULL, PARSE_ERROR, "Parse error: ${e.message}").toString()
        }
        return when (parsed) {
            is JSONObject -> handleOne(parsed, confirmHeader)?.toString()
            is JSONArray -> {
                if (parsed.length() == 0) return error(JSONObject.NULL, INVALID_REQUEST, "Empty batch").toString()
                val out = JSONArray()
                for (i in 0 until parsed.length()) {
                    val item = parsed.opt(i)
                    val r = if (item is JSONObject) handleOne(item, confirmHeader)
                    else error(JSONObject.NULL, INVALID_REQUEST, "Batch item is not an object")
                    if (r != null) out.put(r)
                }
                if (out.length() == 0) null else out.toString()
            }
            else -> error(JSONObject.NULL, INVALID_REQUEST, "Request must be an object or array").toString()
        }
    }

    private fun handleOne(req: JSONObject, confirmHeader: Boolean): JSONObject? {
        val hasId = req.has("id")
        val id: Any = if (hasId) req.opt("id") ?: JSONObject.NULL else JSONObject.NULL
        if (req.optString("jsonrpc") != "2.0") {
            return error(id, INVALID_REQUEST, "jsonrpc must be \"2.0\"")
        }
        val method = req.optString("method", "")
        if (method.isEmpty()) {
            // A JSON-RPC response sent by the client (we never send server->client requests): ignore.
            return if (req.has("result") || req.has("error")) null
            else error(id, INVALID_REQUEST, "method required")
        }
        val params = req.optJSONObject("params") ?: JSONObject()
        // Notifications have no id and never get a response.
        if (!hasId) {
            if (method != "notifications/initialized" && !method.startsWith("notifications/")) {
                Log.d(TAG, "ignoring notification without id: $method")
            }
            return null
        }
        return try {
            when (method) {
                "initialize" -> result(id, initialize(params))
                "ping" -> result(id, JSONObject())
                "tools/list" -> result(id, JSONObject().put("tools", McpToolExecutor.toolDefinitions()))
                "tools/call" -> result(id, callTool(params, confirmHeader))
                "resources/list" -> result(id, JSONObject().put("resources", JSONArray()))
                "prompts/list" -> result(id, JSONObject().put("prompts", JSONArray()))
                else -> error(id, METHOD_NOT_FOUND, "Method not found: $method")
            }
        } catch (e: IllegalArgumentException) {
            error(id, INVALID_PARAMS, e.message ?: "invalid params")
        } catch (e: Exception) {
            Log.w(TAG, "MCP $method failed: ${e.message}")
            error(id, INTERNAL_ERROR, e.message ?: "internal error")
        }
    }

    private fun initialize(params: JSONObject): JSONObject {
        val requested = params.optString("protocolVersion", "")
        val version = if (requested in SUPPORTED_PROTOCOL_VERSIONS) requested else DEFAULT_PROTOCOL_VERSION
        return JSONObject()
            .put("protocolVersion", version)
            .put("capabilities", JSONObject().put("tools", JSONObject()))
            .put("serverInfo", JSONObject().put("name", SERVER_NAME).put("version", SERVER_VERSION))
            .put(
                "instructions",
                "On-device broker for Shizuku shell, Accessibility UI control, Dhizuku device-owner policy " +
                    "and Zebra DataWedge. HIGH-risk tools (exec_shell, perm, set_scopes) need confirm=true."
            )
    }

    private fun callTool(params: JSONObject, confirmHeader: Boolean): JSONObject {
        val name = params.optString("name", "")
        require(name.isNotEmpty()) { "tools/call requires params.name" }
        require(name in McpToolExecutor.TOOL_NAMES) { "Unknown tool: $name" }
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val r = tools.call(name, args, confirmHeader)
        val isError = !r.optBoolean("ok", false)
        return JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", r.toString())))
            .put("structuredContent", r)
            .put("isError", isError)
    }

    private fun result(id: Any, result: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)

    private fun error(id: Any, code: Int, message: String): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))

    companion object {
        private const val TAG = "McpJsonRpc"
        const val SERVER_NAME = "shizuku-ai-action-broker"
        const val SERVER_VERSION = "1.6.0-dev"
        const val DEFAULT_PROTOCOL_VERSION = "2024-11-05"
        val SUPPORTED_PROTOCOL_VERSIONS = setOf("2024-11-05", "2025-03-26", "2025-06-18")

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
    }
}
