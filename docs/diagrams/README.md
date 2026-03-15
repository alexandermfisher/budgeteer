# Architecture & Flow Diagrams

This directory contains Mermaid diagrams documenting the key flows and architecture of the Budgeteer application.

## Diagrams

| Diagram | Description |
|---------|-------------|
| [User Authentication Flow](./user-authentication-flow.md) | Magic link signup/login, session creation, and token refresh |
| [Monzo OAuth Flow](./monzo-oauth-flow.md) | OAuth connection flow for linking Monzo accounts |

## Viewing Diagrams

These diagrams use [Mermaid](https://mermaid.js.org/) syntax which renders natively on GitHub.

**Local viewing options:**
- **VS Code**: Install "Markdown Preview Mermaid Support" extension
- **IntelliJ**: Install "Mermaid" plugin
- **Browser**: Paste into [Mermaid Live Editor](https://mermaid.live/)

## Diagram Types Used

- **Sequence Diagrams**: Show interactions between components over time
- **Flowcharts**: Show decision logic and branching paths

## Related Documentation

- [Security Architecture](../SECURITY-ARCHITECTURE.md)
- [User Authentication Feature](../features/USER-AUTHENTICATION.md)
- [Monzo Token Persistence](../features/MONZO-TOKEN-PERSISTENCE.md)
- [Monzo Auth Flow](../MONZO-AUTH-FLOW.md)
