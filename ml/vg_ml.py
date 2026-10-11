# %% [markdown]
# # VestGuard · learning that generalises (not memorises)
# Goal: find out whether a trained model can beat the 4 interpretable checks **on movements and sessions it has never seen**.
# Three ideas keep the models honest with a small dataset:
# 1. **Physics inputs, not raw axes.** Every input is rotation-invariant (|a|, |ω|, tilt angles), so a model cannot learn how the vest happened to sit that day.
# 2. **Event windows.** The model only looks at the 4 s around a candidate event, like the vest does, so it learns the event, not the trial.
# 3. **Hard tests.** Leave-one-*session*-out, leave-one-*activity*-out (a fall type it has never seen), and every trial played *backwards* (getting up must never look like a fall).

# %%
import glob, numpy as np, pandas as pd, warnings
warnings.filterwarnings('ignore')
FS = 50                      # Hz
PRE, POST = 75, 125          # samples before / after the event (1.5 s / 2.5 s)
FILES = [f for f in sorted(glob.glob('*.csv')) if 'trial_id' in open(f).readline()]   # every recording CSV in this folder
print(FILES)

def load(f):
    d = pd.read_csv(f)
    d['activity'] = d['activity'].astype(str).str.strip('"')
    d['session'] = f.split('/')[-1].replace('.csv', '')
    if 'subject' not in d: d['subject'] = 'S01'
    return d
raw = pd.concat([load(f) for f in FILES], ignore_index=True)
A1 = ['s1_ax', 's1_ay', 's1_az']; G1 = ['s1_gx', 's1_gy', 's1_gz']; A2 = ['s2_ax', 's2_ay', 's2_az']; G2 = ['s2_gx', 's2_gy', 's2_gz']
print(raw.groupby(['session', 'label']).trial_id.nunique())

# %% [markdown]
# ## Per-trial signals in physics units
# Upright reference per session = mean gravity direction while standing still (what the vest's calibration stores).

# %%
def unit(v): return v / np.linalg.norm(v, axis=-1, keepdims=True)
def angle(a, b): return np.degrees(np.arccos(np.clip((unit(a) * unit(b)).sum(-1), -1, 1)))

refs = {}
for s, g in raw.groupby('session'):
    st = g[g.activity.str.contains('Standing still')]
    if not len(st): st = g[g.trial_id == g.trial_id.min()].head(FS)
    refs[s] = (st[A1].mean().values, st[A2].mean().values)

trials = []
for (s, tid), g in raw.groupby(['session', 'trial_id']):
    g = g.sort_values('t_ms').drop_duplicates('t_ms')
    a1, a2 = g[A1].values.astype(float), g[A2].values.astype(float)
    w1, w2 = g[G1].values.astype(float), g[G2].values.astype(float)
    ok = ~np.isnan(a1).any(1)
    a2 = np.where(np.isnan(a2), a1, a2); w2 = np.where(np.isnan(w2), w1, w2)   # lower missing -> copy upper (like the vest)
    t = g.t_ms.values[ok].astype(float); a1, a2, w1, w2 = a1[ok], a2[ok], w1[ok], w2[ok]
    if len(t) < 20: continue
    # the phone drops some packets over Wi-Fi; put every trial back on an even 50 Hz grid
    tg = np.arange(t[0], t[-1], 1000 / FS)
    rs = lambda X: np.stack([np.interp(tg, t, X[:, j]) for j in range(3)], 1)
    a1, a2, w1, w2 = rs(a1), rs(a2), rs(w1), rs(w2)
    if len(a1) < PRE + POST: continue
    r1, r2 = refs[s]
    # gaps longer than 300 ms are kept as a quality note
    gap = np.diff(t).max() if len(t) > 1 else 0
    trials.append(dict(session=s, trial=tid, subject=g.subject.iloc[0], activity=g.activity.iloc[0], label=int(g.label.iloc[0] == 'fall'),
                       a1=a1, a2=a2, w1=w1, w2=w2, r1=r1, r2=r2, gap=gap))
print(len(trials), 'trials')

# %% [markdown]
# ## Event windows
# Candidate events = jolts above 1.6 g (lower than the vest's 2.5 g, so the model also sees softer events), at least 1 s apart.
# A fall trial contributes its biggest event as a **fall** window; everything in an everyday trial is a **not-fall** window.

# %%
def events(m, thr=1.6, gap=FS):
    idx, i = [], PRE
    while i < len(m) - POST:
        if m[i] > thr and m[i] == m[max(0, i - 10):i + 10].max(): idx.append(i); i += gap
        else: i += 1
    return idx

def window(tr, i, rev=False):
    sl = slice(i - PRE, i + POST)
    X = {k: tr[k][sl].copy() for k in ('a1', 'a2', 'w1', 'w2')}
    if rev: X = {k: v[::-1].copy() for k, v in X.items()}
    return X

def features(X, r1, r2):
    m1, m2 = np.linalg.norm(X['a1'], axis=1), np.linalg.norm(X['a2'], axis=1)
    om1, om2 = np.linalg.norm(X['w1'], axis=1), np.linalg.norm(X['w2'], axis=1)
    pre1, post1 = X['a1'][:PRE - 20].mean(0), X['a1'][-25:].mean(0)
    pre2, post2 = X['a2'][:PRE - 20].mean(0), X['a2'][-25:].mean(0)
    tb, ta = angle(pre1, r1), angle(post1, r1)
    return dict(peak1=m1.max(), peak2=m2.max(), dip1=m1[PRE - 50:PRE + 5].min(), jerk1=np.abs(np.diff(m1)).max() * FS / 100,
                gyro1=om1.max(), gyro2=om2.max(), turn1=angle(pre1, post1), turn2=angle(pre2, post2),
                tilt_before=tb, tilt_after=ta, tilt_delta=ta - tb, still_post=m1[-25:].std(), active_pre=m1[:PRE - 20].std(),
                agree=min(m1.max(), m2.max()) / max(m1.max(), m2.max()),
                freefall_ms=float(((m1[PRE - 40:PRE] < 0.6)).sum() * 1000 / FS))

rows, wins = [], []
for k, tr in enumerate(trials):
    m = np.maximum(np.linalg.norm(tr['a1'], axis=1), np.linalg.norm(tr['a2'], axis=1))
    ev = events(m)
    if tr['label'] == 1:
        ev = [int(np.argmax(m[PRE:len(m) - POST]) + PRE)]          # the fall itself
    if not ev:                                                      # quiet trial: one window at its biggest movement
        j = int(np.argmax(m[PRE:len(m) - POST]) + PRE); ev = [j]
    for i in ev:
        for rev in (False, True):
            X = window(tr, i, rev)
            f = features(X, tr['r1'], tr['r2'])
            rows.append(dict(f, k=k, session=tr['session'], subject=tr['subject'], activity=tr['activity'] + (' (backwards)' if rev else ''),
                             base_activity=tr['activity'], reversed=rev, y=0 if rev else tr['label']))
            wins.append(X)
W = pd.DataFrame(rows)
FEATS = [c for c in W.columns if c not in ('k', 'session', 'subject', 'activity', 'base_activity', 'reversed', 'y')]
print(W.groupby(['reversed', 'y']).size())

# %% [markdown]
# ## Baseline: the vest's four checks (Path A), on the same windows

# %%
def rules(f):
    hard = np.maximum(f.peak1, f.peak2) > 2.5
    down = ~((f.tilt_after < 40) & (f.tilt_after < f.tilt_before - 20))
    turn = (f.turn1 >= 45) | ((f.turn1 >= 30) & (f.peak1 >= 6))
    lower = (f.peak2 >= 1.5) & (f.turn2 >= 27)
    return (hard & down & turn & lower).astype(int)
W['rule'] = rules(W)

# %% [markdown]
# ## Models
# * **Logistic regression** – one weight per physics feature (readable).
# * **Decision tree, depth 3** – learns its own if/else rules (readable).
# * **Gradient boosting with monotonic constraints** – more turn / more tilt-down / harder hit can only *raise* the fall score; this blocks nonsense patterns.
# * **Small 1D-CNN** on 6 rotation-invariant signals (|a|, |ω|, tilt of both sensors) with augmentation.

# %%
from sklearn.linear_model import LogisticRegression
from sklearn.tree import DecisionTreeClassifier, export_text
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.preprocessing import StandardScaler
from sklearn.pipeline import make_pipeline

MONO = {'peak1': 1, 'peak2': 1, 'turn1': 1, 'turn2': 1, 'tilt_after': 1, 'tilt_delta': 1, 'still_post': -1}
def models():
    return {
        'logistic': make_pipeline(StandardScaler(), LogisticRegression(C=0.5, class_weight='balanced', max_iter=2000)),
        'tree_d3': DecisionTreeClassifier(max_depth=3, class_weight='balanced', random_state=0),
        'gbm_monotonic': HistGradientBoostingClassifier(max_depth=3, max_iter=150, learning_rate=0.05, class_weight='balanced',
                                                        monotonic_cst=[MONO.get(f, 0) for f in FEATS], random_state=0),
    }

# %%
import torch, torch.nn as nn
torch.manual_seed(0); np.random.seed(0)
def signals(X, r1, r2):
    m1, m2 = np.linalg.norm(X['a1'], axis=1), np.linalg.norm(X['a2'], axis=1)
    o1, o2 = np.linalg.norm(X['w1'], axis=1) / 200, np.linalg.norm(X['w2'], axis=1) / 200
    t1, t2 = angle(X['a1'], r1) / 90, angle(X['a2'], r2) / 90
    return np.stack([m1 - 1, m2 - 1, o1, o2, t1, t2]).astype(np.float32)     # 0 = standing still, upright
SIG = np.stack([signals(X, trials[r.k]['r1'], trials[r.k]['r2']) for X, r in zip(wins, W.itertuples())])

def augment(x):
    x = x.copy()
    for i in range(len(x)):
        x[i, :4] *= np.random.uniform(0.85, 1.15)                                 # harder / softer person
        x[i, 4:] += np.random.uniform(-0.08, 0.08, (2, 1))                       # vest sits a few degrees differently
        x[i] += np.random.normal(0, 0.01, x[i].shape)
        sh = np.random.randint(-10, 11); x[i] = np.roll(x[i], sh, axis=1)        # event not perfectly centred
        if np.random.rand() < 0.15: x[i, [1, 3, 5]] = x[i, [0, 2, 4]]            # lower sensor dropped out
    return x

class Net(nn.Module):
    def __init__(s):
        super().__init__()
        s.f = nn.Sequential(nn.Conv1d(6, 16, 7, padding=3), nn.ReLU(), nn.MaxPool1d(2), nn.Conv1d(16, 24, 5, padding=2), nn.ReLU(),
                            nn.MaxPool1d(2), nn.Conv1d(24, 32, 5, padding=2), nn.ReLU(), nn.AdaptiveMaxPool1d(1), nn.Flatten(), nn.Dropout(0.3), nn.Linear(32, 1))
    def forward(s, x): return s.f(x).squeeze(1)

class CNN:
    def fit(self, idx, y):
        self.net = Net(); opt = torch.optim.AdamW(self.net.parameters(), 3e-3, weight_decay=1e-3)
        pw = torch.tensor((y == 0).sum() / max(1, (y == 1).sum()), dtype=torch.float32)
        lossf = nn.BCEWithLogitsLoss(pos_weight=pw)
        y = np.asarray(y)
        for ep in range(200):
            self.net.train(); xa = augment(SIG[idx]); order = np.random.permutation(len(idx))
            for b in range(0, len(order), 32):
                j = order[b:b + 32]
                xb = torch.tensor(xa[j]); yb = torch.tensor(y[j], dtype=torch.float32)
                opt.zero_grad(); l = lossf(self.net(xb), yb); l.backward(); opt.step()
        return self
    def predict(self, idx):
        self.net.eval()
        with torch.no_grad(): return (torch.sigmoid(self.net(torch.tensor(SIG[idx]))).numpy() > 0.5).astype(int)

# %% [markdown]
# ## Evaluation (trial level: a trial alarms if any of its windows is called a fall)

# %%
def trial_scores(df, pred):
    d = df.assign(p=pred).groupby(['k', 'reversed']).agg(y=('y', 'max'), p=('p', 'max'), act=('activity', 'first')).reset_index()
    f, n = d[d.y == 1], d[d.y == 0]
    return dict(falls=f'{int(f.p.sum())}/{len(f)}', false_alarms=f'{int(n.p.sum())}/{len(n)}',
                missed=', '.join(sorted(set(f[f.p == 0].act))) or '-', false_on=', '.join(sorted(set(n[n.p == 1].act))) or '-')

def run(split_name, train_mask, test_mask):
    out = []
    tr, te = W[train_mask], W[test_mask]
    out.append(dict(split=split_name, model='rules (vest)', **trial_scores(te, te.rule.values)))
    for name, mdl in models().items():
        mdl.fit(tr[FEATS], tr.y); out.append(dict(split=split_name, model=name, **trial_scores(te, mdl.predict(te[FEATS]))))
    gbm = models()['gbm_monotonic'].fit(tr[FEATS], tr.y); pg = gbm.predict_proba(te[FEATS])[:, 1]
    lr = models()['logistic'].fit(tr[FEATS], tr.y); pl = lr.predict_proba(te[FEATS])[:, 1]
    r = te.rule.values
    # hybrids: the rules stay in charge; the model can only veto a rule alarm it is very sure about,
    # or add an alarm the rules missed when it is very sure
    out.append(dict(split=split_name, model='rules + gbm veto (<0.05)', **trial_scores(te, (r & (pg >= 0.05)).astype(int))))
    out.append(dict(split=split_name, model='rules OR gbm (>0.95)', **trial_scores(te, (r | (pg > 0.95)).astype(int))))
    out.append(dict(split=split_name, model='rules + logistic veto', **trial_scores(te, (r & (pl >= 0.05)).astype(int))))
    if CNN_ON:
        cnn = CNN().fit(tr.index.values, tr.y.values)
        out.append(dict(split=split_name, model='cnn_invariant', **trial_scores(te, cnn.predict(te.index.values))))
    return out
CNN_ON = True

results = []
# A) leave one session out
for s in W.session.unique():
    results += run(f'unseen session: {s[-10:]}', W.session != s, W.session == s)
# B) leave one FALL TYPE out (and one everyday type out): can it handle a movement it never saw?
for act in W[W.y == 1].base_activity.unique():
    results += run(f'unseen fall type: {act}', W.base_activity != act, W.base_activity == act)
for act in ['Jogging', 'Jumping', 'Lying down', 'Bending to pick object']:
    if act in set(W.base_activity): results += run(f'unseen everyday: {act}', W.base_activity != act, W.base_activity == act)
R = pd.DataFrame(results)
pd.set_option('display.width', 200); pd.set_option('display.max_colwidth', 60)
print(R.drop(columns=['missed','false_on']).to_string(index=False))
R.to_csv('ml_results.csv', index=False)

# %% [markdown]
# ## What the readable models learned (trained on everything)

# %%
lr = models()['logistic'].fit(W[FEATS], W.y)
coef = pd.Series(lr[-1].coef_[0], index=FEATS).sort_values()
print('Logistic regression weights (standardised; + pushes towards "fall"):'); print(coef.round(2).to_string())
tree = DecisionTreeClassifier(max_depth=3, class_weight='balanced', random_state=0).fit(W[FEATS], W.y)
print('\nDecision tree (depth 3) learned these rules:'); print(export_text(tree, feature_names=FEATS, decimals=1))

# %% [markdown]
# ## What this says (11 Oct 2026, 2 sessions, 1 wearer, 21 falls)
# * **On new sessions and new fall types the four checks are still the best.** They were written from physics, not fitted to these 21 falls, so nothing to forget.
# * **Readable models come close.** Logistic regression and the monotonic gradient-boosting model catch unseen fall types, but raise more false alarms on an unseen session (jogging, bending, played-backwards getting up).
# * **The CNN memorises.** It never catches a fall type it was not trained on. 21 falls are far too few for deep learning; it needs hundreds of falls from many people.
# * **The models agree with the physics.** The strongest learned signals are: the body ends lower than it started (`tilt_delta`), a **free-fall dip** just before the impact (`dip1`, low = falling), lying still afterwards, and both sensors feeling it. The first, third and fourth are already vest checks; the free-fall dip is a good candidate for the next one.
#
# **Next:** record with the Test & record page (many wearers, all 35 tests), drop the CSVs in this folder and run again. When falls reach a few hundred, try the CNN again, ideally pre-trained on a public fall dataset (e.g. SisFall or FallAllD, waist sensor) and fine-tuned on VestGuard data.
