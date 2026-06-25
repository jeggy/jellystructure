# Phase R51 — Global-default Ravilo config + optional per-user override (FR-RV-G1)


> Authored from the design project (`design/app/ravilo-config.html`). Revises the
> **per-user-only** storage default established in **[R16](phase-R16-jellystructure-config-screen.md)**
> and R04/R26.

## Problem

Every Ravilo layout setting was stored **per Jellyfin user** — there was no single layout
that applies to "everyone", and no clear way to express "this one user is different".
Configuring a household meant repeating the same layout for each user.

## Model

- A single **Global layout** is the **default**: every Jellyfin user who does not have their
  own layout sees it.
- A user can be given a **custom layout** that **fully replaces** the global one for them.
  Nothing is inherited piecemeal — a user is **binary**: either *Global* or their *own
  complete* config.

## Config screen

The per-user banner is replaced by a **scope switcher**:

- **Global · all users** (default) / **A specific user** + a user picker. The picker marks
  each user as *global* or *custom*.
- Selecting a user **without** a custom layout shows a lock prompt ("*<name>* uses the
  global layout" + **Create custom layout**) and dims the editable sections until one is
  created.
- Selecting a user **with** a custom layout edits freely; a banner states the override is a
  **full replacement** ("global changes won't reach them") and offers **Remove custom
  layout** (revert to global).
- The Behaviour **interface-language** scope badge follows the switcher ("all users" /
  "this user").

## Storage implication

`RaviloConfig` gains a **global record**; per-user records are **full configs** (not diffs).
Resolution for a TV session = the user's override if present, else the global record. Live
push (R33) targets the affected scope: a global write notifies every user on the global
layout; a per-user write notifies just that user.

## Scope / invariants

- No piecemeal inheritance or per-field overrides — keep the model binary (global ↔ full
  user override) to stay predictable.
- Per-user **interface language** (R19) and skin override (R29) still apply on top of
  whichever layout resolves.

## Mockup

`design/app/ravilo-config.html` (scope switcher, lock prompt, custom-layout banner).
