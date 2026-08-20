# The interface

The app has two primary surfaces: chats, and a single searchable settings page.
The old overview, access, tools and log tabs no longer exist as parallel branches
of the app. Their functions were sorted into settings, and memory is the only
remaining large management view. Everything else works inline.

Chat-local settings sit directly above the input field as a collapsible hairline
extension joined to the composer. Android back, a tap into the chat history, or
another tap on settings closes it. The three-dot menu in a chat closes on an
outside tap and bundles import, pause, write, admin and block, instead of growing
more permanent header buttons.

Settings show supporting text only where something actually needs explaining.
Traits carry short labels with a self-deprecating emoji and a compact slider with
no repeated heading. Access, owner and admin, safety and activity content stays
in bounded inline panels, so opening one creates neither a new page nor an
uncontrolled scroll jump.

## Visual system

- Dark anthracite carries the surface. Grey and white carry the hierarchy.
- Green means running, connected or selected. It is not a decorative background
  for every button.
- `TextLow` stays readable and is used only for secondary detail.
- Interactive targets are at least 48 dp, and icon actions carry semantics and
  labels.
- Radii are restrained. Inputs and chat bubbles are visibly rounded, lists stay
  calm and are not broken into high-contrast individual cards.
- Plain text actions are allowed. Contrast surfaces stay reserved for primary or
  destructive actions.

The shared building blocks are in `ui/BotSurfaces.kt`, colours and shapes in
`ui/theme`.

## Chats

`ChatListScreen` is the start screen. Its top row holds runtime status, uptime,
chat count and settings in one compact line. The status is tappable and starts or
stops the service. A left swipe opens the same settings root, because there is no
second settings branch.

The list uses one shared visible one-second tick for waiting and working times.
Without visible activity that ticker does not exist. Chat rows show the alias or
group name from events and catalogue data already known locally, so there is no
cosmetic profile polling.

An open chat:

- starts at the end with no scroll animation;
- follows newly persisted rows only when the operator is already at the tail;
- shows compact bubbles with the time and delivery state on the same visual line;
- marks outgoing voice messages;
- shows persona changes as forward-looking timeline markers;
- shows memory versions at their real anchor position, with two plain arrows for
  the previous and next memory;
- automatically expands a memory that was just written or changed;
- shows a real empty state instead of a blank black area.

## Flight recorder and chat settings

The flight recorder at the bottom is a full-width floating single-line status
area. Its height does not change between idle, waiting, thinking and showing a
model name. Settings sit as a contrasting icon button inside the right edge of
the field. There is no separate plus button beside it.

The trace block above it shows the stages and tool calls that matter.
Boilerplate such as `Loading reply context`, `Reply context ready` and `Starting
AI processing` is not shown. Media analysis and voice sending get short visible
stages. The block is bounded and scrollable.

The chat settings sheet holds only real per-chat values:

- persona;
- reply speed: global, instant or human;
- proactivity;
- the group trigger, in groups only.

Models, words per minute and memory cadence are global. `Inject context` sits at
the bottom. The panel carries the same visible hairline as the input field.

## Injection and memory

`Inject context` inserts a local `[in your head]` line at the current point in
the timeline. It is never sent to WhatsApp, it stays fixed at that position, it
does not count as a real message or towards the memory cadence, and it does enter
model calls and later memory compression as an ordinary user history turn.

The marker deliberately names no sender and claims no authority. It used to read
`Important context from the operator:`, and the model read exactly that shape as
a prompt injection and refused the note as a hijack attempt. It now reads as
something that is simply true for the persona. Older lines in the old wording are
rewritten to the current form at render time. After that the same history and
retention rules apply as for the lines around it.

Memory content is expandable and editable inside the chat. New revisions open
with an animation, and a revision compare-and-set stops a slow automatic refresh
from overwriting a manual edit. Older revisions stay navigable within a bound.

## Settings

The root starts with `Settings`, a search field and a compact connection
overview: profile and persona, `Connected` or the current recovery status,
exactly one start/stop action, and today's counts for received, sent and waiting.
There is no `Reply automatically` switch and no manual reconnect button.

Below that is a single list ordered by how often things are used. Search filters
global settings together with the access, block, safety, memory, battery and log
shortcuts. Opening safety runs a single-flight recheck automatically. Categories
and work areas are exclusive disclosures. Value lists and sliders float briefly
at the field that opened them and close on an outside tap.

## Setup

`SetupWizard` is one page, not a five-step wizard. It shows:

1. the WhatsApp number;
2. the OpenRouter API key;
3. an optional admin number in a disclosure, and send code;
4. the pairing code;
5. done.

The bot and the WhatsApp session run on the phone. No PC and no server is
involved. After onboarding the API key and the WhatsApp link are separate
disclosures, so changing a key never forces a new setup.

## Navigation

Settings has no back stack and no subpages. Android back closes the open value
editor first, then the open disclosure, then settings. Memory manages its own
bounded view. There are no tab back stacks and no dependency on
`navigation-compose`.

## What the tests do and do not prove

JVM tests check presenters, import and alias logic, and controller helpers. Lint
checks the Compose and accessibility contracts. The real rendering, the feel of
touch, animation and scroll position have to be confirmed visually on a real
phone after installing.
