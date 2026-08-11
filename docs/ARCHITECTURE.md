# Shizuku AI Action Broker Architecture

Flow:

AI chatbot
↓
Structured Action Request
↓
Action Validator
↓
User Confirmation
↓
Allow-listed Action
↓
Shizuku Executor
↓
Result returned to user

The AI never directly executes arbitrary shell commands.
