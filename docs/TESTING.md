# Testing

## Protocol

1. Each trial is labelled as a fall or a daily activity **before** it is performed.
2. The participant wears the vest exactly as an elder would.
3. The vest makes its decision **on board, in real time** — nothing is processed afterwards.
4. The vest's decision is recorded and compared with the label; every miss is reviewed.

**Safety:** all falls were performed by young, healthy team members onto thick mats. No elderly person was asked to fall.

## Activities

- **12 fall types:** forward, backward, lateral left, lateral right, trip while walking, slip, fall onto knees, collapse / syncope, slide down a wall, faint from a chair, fall from bed, fall then get up.
- **10 daily activities**, chosen because they look most like falls (walking, sitting down, standing up, lying down, stairs, bending to pick up an object, and others).

## On-vest results (V2)

| Test set | Trials | Correct | Rate |
|---|---|---|---|
| Falls detected | 150 | 142 | 94.7% |
| Daily activities correctly ignored | 145 | 140 | 96.6% |
| Overall | 295 | 282 | 95.6% |

## System checks

Wi-Fi drop and reconnection without false alarms · one sensor unplugged · "I'm OK" cancel · nobody answers → escalation · "I'm going" response · range inside a house · one hour on battery.

## Offline ML study

Run on the first-generation dataset (6,629 samples, 178 labelled trials, 29 activity types). Gradient-boosting models (CatBoost, GBM) on trial-level features, validated with **GroupKFold by trial** so no trial appears in both training and testing.

| Model | Accuracy | Recall | Precision | AUC |
|---|---|---|---|---|
| CatBoost | 95.3% | 93.8% | 97.8% | — |
| Gradient Boosting | — | — | — | 0.984 |

## Limitations

Young volunteers only so far; supervised field trials with elderly users (with ethical approval) are the next step.
