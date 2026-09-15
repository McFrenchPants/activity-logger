# Visual spec mockups

Mockups for [`docs/UX_VISUAL_SPEC.md`](../../UX_VISUAL_SPEC.md). Static, not a
clickable prototype.

## Files

| File | What it is |
|---|---|
| `activity-ledger-visual-spec.html` | Self-contained canvas (all artboards, pan/zoom). Open in a browser. ~2.7 MB because the canvas viewer is bundled inline. Fonts load from Google Fonts; offline it falls back to system fonts. |
| `*.dc.html` | One file per artboard (source). |
| `canvas.json` | Artboard positions, pages, and titles. |
| `gen.mjs` | Generator that writes every `*.dc.html` and `canvas.json`. **This is the source of truth** — edit it, not the generated files. |

Hosted copy (private to the owner until shared):
https://claude.ai/artifact/BHMxyMi5PRfpFC8BRHmN7H

## Regenerating

```bash
node docs/design/visual-spec/gen.mjs
```

This rewrites the artboard files and `canvas.json` in place. Rebuilding the
single-file canvas (`activity-ledger-visual-spec.html`) needs the Claude Code
`design` skill; ask the agent to re-seed the canvas from this folder.

Edits made in the hosted canvas's editor are not written back here — pull
them back into `gen.mjs` if they should become part of the spec.

## Artboards

- Phone · Capture: `Main` (Log ready), `HomeListening`, `HomeSuccess`,
  `NeedsReview`, `AiUnavailable`, `CaptureFailure`
- Phone · History, Ask, Correction: `History`, `OccurrenceSheet`,
  `ActivityDetail`, `Correction`, `AskHistory`, `EmptyStates`
- Phone · Settings & accessibility: `Settings`, `SettingsUnsupported`,
  `Accessibility`
- Watch: `WatchStates`
- Design system & decisions: `DesignSystem`, `Decisions`

Phone frames are 412×915dp (Pixel 10 Pro class). No status bar or keyboard is
drawn. Watch faces are drawn at 1.3× a ~230dp round display.
