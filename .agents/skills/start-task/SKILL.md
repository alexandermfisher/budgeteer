---
name: start-task
description: Start a new task — promotes a backlog/queue item to In Progress, creates the task subfolder and plan.md, and updates the kanban index.
argument-hint: "[task-name or description]"
allowed-tools: Read, Write, Edit
---

# start-task

Set up everything needed to begin a new task.

## Steps

1. **Read `.agents/tasks/tasks.md`** to understand the current board state.

2. **Identify the task** to start:
   - If an argument was given, match it to an item in Queue or Backlog.
   - If no argument was given, ask the user which task they want to start.

3. **Derive a slug** from the task name: lowercase, words separated by hyphens, no special characters.
   - e.g. "Phase 3: Token Auto-Refresh" → `token-auto-refresh`

4. **Check if a subfolder already exists** at `.agents/tasks/open/<slug>/`.
   - If it does, read the existing `plan.md` and skip to step 6.
   - If it doesn't, create the folder and write a `plan.md` (step 5).

5. **Create `.agents/tasks/open/<slug>/plan.md`** with this structure:
   ```
   # <Task Name>

   > **Priority:** <priority> | **Estimate:** <estimate> | **Status:** In Progress

   ## Goal

   <one paragraph describing what this task achieves and why>

   ## Scope

   - [ ] <first concrete step>
   - [ ] <next step>
   - [ ] ...
   ```
   Populate Goal and Scope from whatever is known — the queue entry, backlog notes, or ask the user for detail if the task is thin.

6. **Update `.agents/tasks/tasks.md`**:
   - Move the task row from its current section (Backlog or Queue) into **🚀 In Progress** at the top.
   - If it was a Backlog item (no existing row in Queue), add it to In Progress with a link: `[plan](open/<slug>/plan.md)`.
   - If it already had a Queue row, move that row and ensure the plan link is present.
   - Update the `Last updated` date at the bottom.

7. **Confirm to the user**:
   - Show the path to the plan file.
   - Print the current In Progress section so they can see the board state.
   - Remind them to update `.agents/memory.md` with session focus if starting a new session.
