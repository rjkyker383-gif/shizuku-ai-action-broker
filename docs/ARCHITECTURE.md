# Shizuku AI Action Broker Architecture

Flow:

```
AI chatbot / PC companion
        ↓
Structured Action Request (schemas/action.schema.json)
        ↓
ActionAllowList validation
        ↓
BrokerServer (127.0.0.1:17999) → ActionRouter
        ↓
┌───────────────────┬────────────────────┬────────────────────┐
│ AccessibilitySvc  │ ShizukuBridge      │ DelegatedAdmin     │
│ tap/swipe/text/UI │ Shizuku API / rish │ scopes / perm /    │
│                   │ / sh fallback      │ suspend            │
└───────────────────┴────────────────────┴────────────────────┘
        ↓
Result JSON returned to caller
```

`ShizukuGateway` exposes binder/permission/UID status for the UI and for
`ShizukuBridge` readiness checks. The AI should prefer structured allow-listed
actions; arbitrary shell is HIGH risk and gated by the allow-list.
