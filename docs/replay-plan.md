# Replaying completed games — plan

> Status: **Shipped** (2026-06-25). Read-only board time-travel by clicking
> half-moves in the move log of a **finished** game. The game-service already
> stores every ply's position (`GameSnapshot.history`), so this is a pure
> *projection* of existing state — no move re-computation, and no chess logic on
> the client (`rules`/`codec` are JVM-only and can't run in Scala.js). Built as
> the **substrate** that the post-game-analysis feature layers onto without
> depending on it. See **"Tie-ins"**.

## Context
A completed game currently dead-ends: the board shows the final position and the
move log is inert. We want to step back through the game. On a completed game
(and only then), clicking any half-move in the move log jumps the board to that
position. The **active** move is marked with an emphatic scribble underline (full
ink); moves **after** it are muted; **hovering** any move shows the same underline
in muted ink (a preview). This is the review/learning surface the analysis
feature builds on.

## What exists vs what's missing
- ✅ **Every intermediate position is already stored** — `GameSnapshot.history:
  List[HistoryEntry]` (`HistoryEntry(move, state, preColor, san)`), plus
  `initialState` and the `moveLog` projection (`game-service/.../model/SessionState.scala`,
  history/redo/initialState ~45-51, `HistoryEntry` 21-26, `moveLog` 58-59,
  `fromHistory` 161-190). Replay iterates `history.reverse` — **no re-applying
  moves**, exactly what load already does.
- ✅ **One board source** — `stateVar: Var[Option[BoardStateDto]]`
  (`web-ui/.../Main.scala:27`); board grid (`~1026`), check highlight (`~1162`),
  captured pieces (`~930`). `gameOverSignal` already derives "completed"
  (`Main.scala:258` — `status.kind != "playing"`).
- ✅ **Read-only board exists** — `board(readOnly = true)` for spectators;
  `renderSquare` (`~1034`) disables drag/click when read-only.
- ✅ **Move-log render** — `renderMoveLog` (`Main.scala:1294`) groups via
  `Logic.groupMovesByTwo`; each half-move is a `Components.newsprintClip("move-san")(san)`.
- ✅ **Reusable projection + sidecar pattern** — `GameState → BoardStateDto` mapping
  (`GrpcMappers`/`WebBoardView`), `BoardStateDto.encodeBytes` boopickle bytes
  (`api/.../BoardStateDto.scala:107`), and the `AnnotationsDto` gRPC sidecar
  (`BoardStateDto.scala:236-275`) as the precedent for shipping a per-game extra.
- ❌ **No per-ply data on the wire** — `MoveEntryDto(color, san)` only; no replay
  rpc/endpoint; client can't reconstruct (`rules`/`codec` JVM-only).

## Core design — server projects history, client scrubs a cached vector
The game-service returns the whole position history once; the client caches it and
scrubs locally (instant, offline, no per-click round-trip).

- **Shared nav state** (the substrate the other features read):
  - `replayFramesVar: Var[Vector[ReplayFrame]]` — cached on game completion.
  - `activePlyVar: Var[Int]` — half-moves applied, `0..N`, default `N` (final
    position; nothing changes visually until the user clicks).
- **`boardViewSignal`** — the crux. Position-derived UI reads the *selected frame*
  when replaying, else `stateVar`: the board grid, check highlight, captured
  pieces, and turn indicator repoint to `boardViewSignal`. **Game-meta stays on
  `stateVar`**: the move log shows the full list (with active/muted styling) and
  the result/status card keeps showing the *final* outcome — replay never makes it
  read "playing". The live `stateVar` keeps receiving SSE untouched.

## Backend (read-only, mirrors existing patterns)
1. **proto** (`proto/.../game_service.proto`): add
   `rpc ReplayGame(GameIdRequest) returns (ReplayReply)` with
   `ReplayReply { string gameId; repeated ReplayFrame frames }`,
   `ReplayFrame { int32 moveIndex; bytes boardState; string san }`. Mirrors
   `ListActiveGamesReply` (`repeated`) + `StateReply.boardState` (boopickle bytes).
2. **game-service** (`GrpcServer.replayGame`): fetch the live session, iterate
   `GameSnapshot.history.reverse` (prepend an initial-position frame at index 0),
   project each ply's `GameState → BoardStateDto` reusing the existing mapper, and
   `BoardStateDto.encodeBytes` each. **Pure read — the session is never mutated**
   (unlike undo/redo, which pop the `history`/`redoStack` deques).
3. **gateway**: `TracingGameServiceClient.replayGame` (mirror the `undo` wrapper);
   `Endpoints.getReplay` — **read-only, no `sessionHeader`** (mirror `getState`/
   `getExport`, `Endpoints.scala:49-52`) → `ReplayResponse(gameId,
   List[ReplayFrame{moveIndex, BoardStateDto, san}])`; `WebController` handler
   decodes the frame bytes. New DTOs (`ReplayFrame`, `ReplayResponse`) in
   `api/.../BoardStateDto.scala`.

## Web-ui (`Main.scala`, pure bits in `Logic.scala`)
- On `gameOverSignal` → true, fetch `GET /api/games/{id}/replay` **once** into
  `replayFramesVar` (reset when a game returns to "playing", reusing the existing
  `gameOverSignal.changes.filter(!_)` reset hook at `~445`).
- Define `boardViewSignal` and repoint the position-derived readers (board grid,
  check, captured pieces, turn dot) to it; leave move-log + result on `stateVar`.
- `renderMoveLog`: attach a click handler per half-move **only when `gameOver`**
  (set `activePlyVar`); add reactive classes `is-active` / `is-future`.
- Board stays `readOnly` during replay (already true for completed/spectator).
- Spectators/Watch replay for free (read-only endpoint, move log already shown via
  `moveLogContainer(showInput = false)`).
- Optional niceties (flagged, not core): "to start"/"to end" controls, ←/→
  keyboard stepping, highlight the just-played move's from/to squares.

## Move-log interaction + styling
- **Pure ply mapping in `Logic.scala`** (unit-tested): flat index `i` →
  *active* when `i == activePly - 1`, *future* (muted) when `i >= activePly`;
  click → `activePly = i + 1`.
- **CSS** (`gateway/.../tailwind/bespoke.css`): `.move-san.is-active` → emphatic
  scribble underline, full ink; `.move-log.replayable .move-san:hover:not(.is-active)`
  → same underline in **muted** ink + pointer cursor; `.move-san.is-future` →
  muted ink (`var(--text-muted)`).
- **Emphatic scribble underline**: a thicker / double-stroke variant of the
  existing `.scribble-underline` (`bespoke.css:2363`, `/web/scribble-underline.svg`
  mask) under the `#hand-drawn` filter — distinct from the lighter nav underline.
- The move-log row keeps an **optional per-ply annotation slot** for the analysis
  feature's eval badge (see Tie-ins).

## Tie-ins with analysis (independent, but meet here)
Replay and analysis ship independently and meet at small, additive seams.
1. **Analysis layers a parallel per-ply map.** Post-game eval/quality lives in a
   separate `Map[ply, …]` (computed via `Search.evaluate`/`bestMoves`,
   `bot-engine/.../Search.scala:68-101`) shipped through an `AnnotationsDto`-style
   sidecar and painted into the move-log's annotation slot. Replay provides the
   clickable log + `activePlyVar`; it never computes evals.
2. **Shared files (additive, merge-prone):** `BoardStateDto.scala` (this adds the
   replay DTOs; analysis may add eval), `renderMoveLog`, and the result card.

## Files
- `proto/src/main/protobuf/pichess/game_service.proto` — `ReplayGame` rpc + messages.
- `game-service/.../gameservice/GrpcServer.scala` (+ `GrpcMappers`/`WebBoardView`) — `replayGame`.
- `api/.../api/BoardStateDto.scala` — `ReplayFrame`/`ReplayResponse` DTOs; `Endpoints.scala` — `getReplay`.
- `gateway/.../gateway/TracingGameServiceClient.scala`; `gateway/.../controller/WebController.scala`.
- `web-ui/.../webui/Main.scala` (fetch/cache, `boardViewSignal`, move-log click + classes); `web-ui/.../webui/Logic.scala` (ply mapping).
- `gateway/.../tailwind/bespoke.css` (emphatic underline + `is-active`/`is-future`/hover); `docs/design.md` (document the replay states + underline).

## Testing
- **game-service**: `replayGame` returns `N+1` frames with the correct positions
  (initial + after each move); the session is unchanged afterward (100% gate).
- **gateway**: `GET /api/games/{id}/replay` returns frames; unknown id → 404.
- **`Logic`** (web-ui, zio-test): the pure ply mapping (active/future/click→ply).
- **Manual**: finish a game → click moves (board jumps), later moves muted, hover
  preview; spectate a finished game → same; mid-replay the result card still shows
  the final outcome.

## Decisions (locked 2026-06-25)
1. **Server replay, client-cached vector** — one `ReplayGame` fetch on completion;
   instant local scrubbing; read-only (no live-game mutation).
2. **Endpoint open for any game**; the client gates interaction on "completed".
3. **Replay substrate only now** — eval badges belong to the parallel analysis
   feature; this defines and leaves its seams.
4. **Active-move marker = emphatic scribble underline** (thicker / double-stroke
   `scribble-underline` mask under `#hand-drawn`): full ink active, muted ink on
   hover; later moves muted.
