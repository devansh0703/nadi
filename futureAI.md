# Nadi — Future AI Features (on-device, Qwen3-VL-4B)

A high-level menu of AI features to layer on top of Nadi's existing on-device
vitals (camera rPPG heart rate/stress, IMU respiration/SCG/tremor) and workout
(sensor rep counting, running) engines.

**Positioning:** everything below is a **wellness summary / coach**, never a
diagnosis. Nadi is not a medical device (see `RULES.md` §8) — that boundary
shapes every feature, not just the disclaimer text.

**Inference:** Qwen3-VL-4B on the Hexagon NPU, on-device and offline. One note
to keep in mind at a high level: a 4B model on a phone NPU is ambitious, so
lean, short, templated tasks will always out-perform open-ended ones. The
*what to build* is here; the *how* (prompts, schemas, quantization) is yours to
decide.

---

## 1. Reports & insights

1. **Vital-session wellness report (PDF)** — turn a measurement session into a
   clean, shareable, lab-report-looking PDF with a plain-language summary,
   green/amber wellness bands, a "vs your last 7 days" trend, and an escalation
   footer. Shareable to WhatsApp/Gmail (the de-facto channels in India).
2. **Daily / weekly wellness score** — one number that blends HR, HRV, stress,
   breathing quality, activity and sleep into a simple "today you're at 82 / 100"
   with a one-line reason.
3. **Personal baseline learning** — instead of comparing the user to population
   tables, learn *their* normal range over weeks and flag deviations from
   themselves ("lower than *your* usual").
4. **Trend & anomaly summaries** — auto-written weekly digest: "resting HR crept
   up 4 bpm this week while HRV dropped; consider a lighter training load."
5. **Recovery / readiness score** — HRV and resting-HR-based "are you recovered
   enough to train hard today?" readout, fused with the workout dashboard.
6. **Lifestyle correlation insights** — cross-link vitals with workouts, diet
   logs, and self-reported sleep to surface patterns ("days you sleep < 6 h,
   your resting HR is +6 bpm").
7. **Doctor-visit summary card** — a compact, jargon-free snapshot of the last
   30 days of vitals to hand a clinician, with normal-range context for each.

---

## 2. Conversational assistant

1. **Health-vitals Q&A** — ask questions about your *own* readings ("why is my
   heart rate higher today?") answered from your actual history, not generic web
   answers; refuses clearly when the data can't support the question.
2. **Wellness glossary / explainer** — "what is HRV?", "what's my stress score
   measuring?" in plain language and Indian languages.
3. **Voice assistant** — full hands-free loop (speech in → answer + speech out)
   for the health, diet and workout questions above.
4. **Health education** — short, regional-language explainers on the things
   India struggles with most: blood pressure, diabetes, anemia, Vit-D/B12,
   hydration, sleep.
5. **Symptom journal** — a low-friction voice/text diary ("mild headache since
   morning") that the AI summarizes into patterns over time, paired with the
   right "see a doctor" escalation when warranted.
6. **Emergency info card** — generate a one-tap card (name, blood group,
   conditions, medications, allergies, emergency contact) for first responders,
   in the user's language.
7. **Medication reminder + Q&A** — reminders and "when should I take this /
   with food?" style Q&A (informational only, never dosing advice).
8. **Goal-setting dialogue** — a short structured conversation that ends in a
   concrete, locally-stored goal (weight, workout cadence, meal target).

---

## 3. Workout & fitness coach

1. **Personalised workout plan** — generate a weekly plan from goal, experience,
   **available equipment**, days per week and session length, mapped only onto
   exercises the app already counts.
2. **Adaptive progression** — auto-suggest +1 rep / +2.5 kg / +1 set when past
   sets show the user is ready, with a plain-language "why".
3. **Recovery-aware training** — soften or harden the next session based on the
   heart dashboard's HRV/resting-HR signal.
4. **Run pacing coach** — pace strategy and stamina guidance from GPS route,
   cadence and heart-rate trends ("slow your first km; your HR spikes early").
5. **Exercise form coach** *(optional, camera-based)* — a separate "mirror mode"
   for squat/push-up form cues. Distinct from the sensor rep counter (which
   stays camera-free per `RULES.md` §4); gated and opt-in.
6. **Yoga / mobility sessions** — guided yoga and stretching plans (deep India
   fit), with rep/breath-style progression where the app can measure it.
7. **Breathing & stress sessions** — AI-guided breathing and breathwork that
   uses the existing IMU respiration signal for real-time pacing feedback and
   post-session stress-reduction summaries.
8. **Fitness-age / cardio estimate** — a light VO₂max-style wellness estimate
   from resting HR and tracked running, presented as "fitness age" for
   motivation, not a medical metric.

---

## 4. Diet & nutrition (India-first)

1. **Food photo → dish + nutrition** — photograph a plate, get the **Indian dish
   name** and a macro estimate (cal / protein / carbs / fat) as a *range* the
   user can edit. The clearest justification for the vision-language model.
2. **Personalised Indian meal plan** — plans built on real Indian staples, with
   axes for: veg / non-veg / egg-itarian / Jain / sattvic, region, and budget
   tier.
3. **Protein-gap tracking** — a visible daily protein counter, because Indian
   vegetarian diets chronically run short; nudges pulses, paneer, dahi, soya,
   eggs, nuts.
4. **Condition-aware diets** — BP-aware, diabetes-aware, and anemia-aware meal
   suggestions (the highest-value health themes in India).
5. **Lab-report import (OCR)** — scan a blood report and pull in the values that
   matter most in India (Hb, fasting glucose, HbA1c, lipid profile, TSH,
   **Vit-B12, Vit-D**), stored as "your lab values" for trending — never
   re-interpreted as diagnosis.
6. **Hydration & micronutrient nudges** — rare, weather- and vitals-informed
   tips ("heat + elevated resting HR — are you hydrating?"), never nagging.
7. **Religious-fasting presets** — editable presets for Ekadashi, Navratri,
   Shravan, Ramadan etc. rather than baked-in rules, so the planner adapts to
   the user's calendar.

---

## 5. Vision-language capabilities

The few places where the *vision* part of the model earns its keep:

1. **Food recognition & portioning** (→ §4.1).
2. **Lab-report OCR** (→ §4.5).
3. **Measurement lighting/coaching** — judge rPPG lighting quality from the
   camera frame itself ("face unevenly lit — move toward a window"), replacing
   the lying ambient-light sensor (per `RULES.md` §3).
4. **Hold-still / framing guidance** — read the live face ROI to coach the user
   into a steady, well-lit measurement.
5. **Progress tracking** *(optional, low priority)* — periodic front-photo for
   consistency (posture, framing) only; explicitly **not** body-composition
   or medical appearance analysis.

Out of scope on purpose: skin-symptom screening, mood/emotion reading, and any
appearance-based health claim — those cross the medical-device line.

---

## 6. Context & environment awareness

1. **AQI-aware outdoor advice** — read local air quality and coach outdoor runs
   and breathwork around it ("AQI 310 — consider indoors today"). Huge for
   Indian metros.
2. **Weather & season awareness** — heat, humidity and monsoon-aware hydration,
   sleep and workout guidance.
3. **Seasonal wellness tips** — monsoon/flu-season-specific, region-aware
   reminders (immunity, hydration, ventilation).
4. **Sleep hygiene coach** — guide sleep from self-reported hours + HRV/stress
   trends (the app doesn't measure sleep directly, so stay self-report-first).

---

## 7. Engagement & gamification

1. **Habit streaks** — measurement and workout streaks with AI-written
   encouragement.
2. **AI-generated challenges** — short, personalised weekly challenges built
   from the user's actual gaps ("you skip hydration on hot days — 7-day water
   streak").
3. **Progress storytelling** — milestone write-ups ("first month: resting HR
   down 4 bpm") to sustain motivation.
4. **Family / household profiles** — multiple local profiles so one phone serves
   a household, with an elder-care mode (below).

---

## 8. India-specific requirements (apply to everything)

1. **Languages** — Hindi + English first, then Tamil, Telugu, Bengali, Marathi,
   gated by a per-language quality check (assume nothing about the model's Indic
   ability until it's verified).
2. **Offline-first** — models bundled or one-time-download; inference runs with
   no network.
3. **Regional + budget-aware diet and plan content** — no quinoa-and-salmon
   defaultism.
4. **Units & norms** — reference bands anchored to Indian adult data where it
   exists, and flagged when generic.
5. **Pricing** — free tier = core vitals + report; subscriptions gate the
   coach/diet features, at Indian price points (UPI-friendly if ever monetised).
6. **Devices** — AI gated to the same NPU class as `PulseML`; on unsupported
   devices hide AI tiles rather than adding a cloud fallback.
7. **Elder-care mode** — large text, voice-first, simplified summaries, and a
   "share with family" path for older users (a meaningful Indian-segment play).
8. **Trust copy** — wellness disclaimer on every surface and a hard-coded
   "see a doctor / call 108" escalation that never comes from the model.

---

## 9. Guardrails & non-negotiables

Carried forward from `RULES.md` and applied to every AI feature:

- **Wellness, not medicine** — nothing diagnoses; the report is a *wellness
  summary* with green/amber bands and a disclaimer.
- **No camera in the workout dashboard** — the sensor rep counter stays
  camera-free; any vision is a separate opt-in mode.
- **Never fabricate** — when the model can't ground an answer in real data, it
  says so; use ranges when uncertain.
- **On-device only** — no face, plate, report or signal leaves the phone.
- **NPU-or-nothing** — same hard-error philosophy as `PulseML`; no cloud/CPU
  fallback for the AI path.
- **No nagging** — proactive tips are opt-in, rare, and measurement-backed.

---

## Priority order (high level)

| Phase | Focus | Note |
|---|---|---|
| **P0** | Reports & insights (§1), health Q&A (§2) | Highest value, no vision needed, reuses existing vitals |
| **P1** | Workout coach (§3.1–3.5), diet plan (§4.2–4.4) | Text-only, big India value |
| **P2** | Food photo (§4.1), lab OCR (§4.5), ROI coaching (§5.2–5.4) | The features that earn the vision model |
| **P3** | Voice (§2.3), context/seasonal (§6), gamification (§7) | Polish and retention layers |
| **P4** | Elder-care (§8.7), AQI-aware (§6.1), family profiles (§7.4) | Segment & retention expansions |