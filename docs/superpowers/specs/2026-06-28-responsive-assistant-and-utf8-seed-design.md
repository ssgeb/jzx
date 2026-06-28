# Responsive Assistant and UTF-8 Seed Design

## Goal

Keep the intelligent assistant launcher and drawer usable across viewport size
changes, establish a responsive baseline for the application shell, and prevent
UTF-8 business seed data from being decoded with the Windows platform charset.

## Responsive Behavior

The launcher remains draggable and persists its last position. On mount and on
every viewport resize, its saved coordinates are clamped to the visible area
using the launcher's measured dimensions and a small safe inset. Corrected
coordinates are written back to local storage so the problem does not recur on
the next visit.

The assistant drawer uses the current viewport width rather than constants
computed once at module initialization. Desktop widths remain user-resizable
within a responsive range. At phone widths the drawer fills the viewport and
disables the resize affordance. A resize event immediately clamps a previously
saved panel width to the new valid range.

The existing application visual language is preserved. The shell uses fluid
gaps, padding, and type sizes with `clamp()`, while existing grid and breakpoint
behavior remains intact. This is a targeted responsive baseline, not a redesign
of every business page.

## UTF-8 Seed Data

Both custom `ResourceDatabasePopulator` instances explicitly set their SQL
script encoding to UTF-8. Spring's `spring.sql.init.encoding` does not configure
populators constructed manually, which allowed UTF-8 scripts to be decoded with
the Windows platform charset.

The business seed scripts are idempotent and update deterministic task IDs. A
backend restart with business seeding enabled therefore repairs the affected
seed rows without deleting records. No broad conversion query or destructive
database operation is required.

## Error Handling

- Missing or malformed launcher position data falls back to the default
  bottom-right placement.
- Viewports smaller than the desktop minimum drawer width receive a full-width
  drawer instead of horizontal overflow.
- Database repair is limited to existing idempotent seed upserts; startup still
  fails according to the configured seed error policy if a script cannot run.

## Verification

- Reproduce an off-screen saved launcher coordinate, reload, and verify the
  launcher bounding box is inside the viewport.
- Resize between 1920, 1280, 768, and 375 pixel widths and verify the launcher,
  drawer, and application shell do not create horizontal overflow.
- Run frontend contract tests and a production frontend build.
- Run Java tests covering explicit populator encoding.
- Restart the backend with business seeding enabled and query distinct
  `detection_task.region` values and hexadecimal bytes.
- Confirm the known mojibake city values are absent and valid UTF-8 city values
  remain present.

## Non-Goals

- Replacing the existing application design system.
- Redesigning every business page in this change.
- Modifying non-seed user-entered records through heuristic text conversion.
