from pathlib import Path
import re

root = Path('decoded')

def read(p):
    return p.read_text(encoding='utf-8')

def write(p, s):
    p.write_text(s, encoding='utf-8')

# 1) Old panel-controlled web endpoints -> local bridge.
p = root / 'smali/com/ApiTonny.smali'
s = read(p).replace('http://ibous.epsplay.fun/bigger/api/', 'http://127.0.0.1:8765/bigger/api/')
write(p, s)

# 2) Visual config/image API -> local bridge.
p = root / 'smali/com/bumptech/glide/load/engine/Api.smali'
s = read(p)
s = s.replace('http://ibous.epsplay.fun/bigger/img/api.json', 'http://127.0.0.1:8765/bigger/img/api.json')
s = s.replace('http://ibous.epsplay.fun/bigger/img/', 'http://127.0.0.1:8765/bigger/img/')
write(p, s)

# 3) DNSContainer native constructor -> ordinary Java constructor.
p = root / 'smali/com/andyhax/DNSContainer.smali'
s = read(p)
s, count = re.subn(
    r'\.method public native constructor <init>\(\)V\s*\.end method',
    '.method public constructor <init>()V\n    .locals 0\n    invoke-direct {p0}, Ljava/lang/Object;-><init>()V\n    return-void\n.end method',
    s,
    count=1,
)
if count != 1:
    raise SystemExit('DNSContainer constructor patch point not found')
write(p, s)

# 4) Start local bridge before visual API fetch.
p = root / 'smali/com/flextv/livestore/apps/LTVApp.smali'
s = read(p)
needle = '''    invoke-super {p0}, Landroid/app/Application;->onCreate()V

    invoke-direct {p0}, Lcom/flextv/livestore/apps/LTVApp;->setarlayout()V'''
repl = '''    invoke-super {p0}, Landroid/app/Application;->onCreate()V

    invoke-static {p0}, Lcom/bigger/local/LocalBridge;->start(Landroid/content/Context;)V

    invoke-direct {p0}, Lcom/flextv/livestore/apps/LTVApp;->setarlayout()V'''
if needle not in s:
    raise SystemExit('LTVApp onCreate patch point not found')
write(p, s.replace(needle, repl, 1))

# 5) Theme from local bridge + successful-login report on HomeActivity entry.
p = root / 'smali/com/flextv/livestore/activities/HomeActivity.smali'
s = read(p)
theme_old = '''    new-instance v1, Lcom/flextv/livestore/helper/PreferenceHelper;

    invoke-direct {v1, p0}, Lcom/flextv/livestore/helper/PreferenceHelper;-><init>(Landroid/content/Context;)V

    .line 5
    invoke-virtual {v1}, Lcom/flextv/livestore/helper/PreferenceHelper;->getSharedPreferenceAppInfo()Lcom/flextv/livestore/models/AppInfoModel;

    move-result-object v1

    invoke-virtual {v1}, Lcom/flextv/livestore/models/AppInfoModel;->getTheme()Ljava/lang/String;

    move-result-object v1'''
theme_new = '''    invoke-static {p0}, Lcom/bigger/local/LocalBridge;->getTheme(Landroid/content/Context;)Ljava/lang/String;

    move-result-object v1'''
if theme_old not in s:
    raise SystemExit('HomeActivity theme patch point not found')
s = s.replace(theme_old, theme_new, 1)
super_old = '''    invoke-super {p0, p1}, Lcom/flextv/livestore/apps/BaseActivity;->onCreate(Landroid/os/Bundle;)V

    invoke-static {p0}, Lcom/StudioLiveCode/View/CustomDialog;->showDialog(Landroid/content/Context;)V'''
super_new = '''    invoke-super {p0, p1}, Lcom/flextv/livestore/apps/BaseActivity;->onCreate(Landroid/os/Bundle;)V

    invoke-static {p0}, Lcom/bigger/local/LocalBridge;->noteLoginSuccess(Landroid/content/Context;)V

    invoke-static {p0}, Lcom/StudioLiveCode/View/CustomDialog;->showDialog(Landroid/content/Context;)V'''
if super_old not in s:
    raise SystemExit('HomeActivity success hook not found')
s = s.replace(super_old, super_new, 1)
write(p, s)

# 6) Block/expiry check before native Xtream login.
p = root / 'smali/com/andyhax/veeepeeen/ui/login/LoginActivity$5.smali'
s = read(p)
login_point = '''    move-result-object v3

    iget-object v4, p0, Lcom/andyhax/veeepeeen/ui/login/LoginActivity$5;->this$0:Lcom/andyhax/veeepeeen/ui/login/LoginActivity;'''
login_new = '''    move-result-object v3

    iget-object v5, p0, Lcom/andyhax/veeepeeen/ui/login/LoginActivity$5;->this$0:Lcom/andyhax/veeepeeen/ui/login/LoginActivity;

    invoke-static {v5, v2, v3}, Lcom/bigger/local/LocalBridge;->isLoginAllowed(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)Z

    move-result v0

    if-nez v0, :bridge_login_allowed

    iget-object v0, p0, Lcom/andyhax/veeepeeen/ui/login/LoginActivity$5;->val$loadingProgressBar:Landroid/widget/ProgressBar;

    const/16 v1, 0x8

    invoke-virtual {v0, v1}, Landroid/view/View;->setVisibility(I)V

    const-string v0, "Acesso bloqueado ou assinatura vencida."

    const/4 v1, 0x1

    invoke-static {v5, v0, v1}, Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;

    move-result-object v6

    invoke-virtual {v6}, Landroid/widget/Toast;->show()V

    return-void

    :bridge_login_allowed
    invoke-static {v5, v2, v3}, Lcom/bigger/local/LocalBridge;->noteLoginAttempt(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)V

    iget-object v4, p0, Lcom/andyhax/veeepeeen/ui/login/LoginActivity$5;->this$0:Lcom/andyhax/veeepeeen/ui/login/LoginActivity;'''
if login_point not in s:
    raise SystemExit('Login click patch point not found')
write(p, s.replace(login_point, login_new, 1))

# 7) Bypass dead legacy bootstrap: LoginActivity becomes launcher.
p = root / 'AndroidManifest.xml'
s = read(p)
main_pat = re.compile(r'(<activity[^>]*android:name="com\.andyhax\.MainActivity"[^>]*>).*?(</activity>)', re.S)
m = main_pat.search(s)
if not m:
    raise SystemExit('MainActivity manifest block not found')
main_open = m.group(1)
s = s[:m.start()] + main_open[:-1] + ' />' + s[m.end():]
login_self = re.search(r'<activity([^>]*android:name="com\.andyhax\.veeepeeen\.ui\.login\.LoginActivity"[^>]*)/>', s)
if not login_self:
    raise SystemExit('LoginActivity manifest entry not found')
login_block = '''<activity{}>
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LEANBACK_LAUNCHER"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>'''.format(login_self.group(1))
s = s[:login_self.start()] + login_block + s[login_self.end():]
write(p, s)

joined = '\n'.join(
    x.read_text(encoding='utf-8', errors='ignore')
    for x in [root/'smali/com/ApiTonny.smali', root/'smali/com/bumptech/glide/load/engine/Api.smali']
)
if 'ibous.epsplay.fun' in joined:
    raise SystemExit('Legacy panel host still present in patched control files')
if '127.0.0.1:8765' not in joined:
    raise SystemExit('Local bridge URL missing after patch')
print('Patch phase OK')
