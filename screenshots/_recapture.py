"""
Quick targeted recapture for screenshots with incorrect content:
  09_sap_lookup_success  — success popup timing fix
  04_settings_mid        — scroll not deep enough
  05_settings_bottom     — scroll not deep enough
"""
import subprocess, time, uuid
from pathlib import Path

ADB = r'C:\Users\JonathanSystemOne\AppData\Local\Android\Sdk\platform-tools\adb.exe'
HERE = Path(r'c:\Github\PPNAM-Station-1-App\PPNAM_Station_1_AA\screenshots')
IMGS = HERE / 'sop_images'
APP = 'com.sysone.scanner'
PO  = '220012017'
PWD = 'Mit@s_'
SCENARIO = HERE / 'scenario.txt'

def adb(*a, check=True):
    return subprocess.run([ADB,*a], capture_output=True, text=True, check=check)
def sh(cmd):   return adb('shell', cmd, check=False).stdout
def tap(x,y):  sh(f'input tap {x} {y}')
def swipe(x1,y1,x2,y2,ms=600): sh(f'input swipe {x1} {y1} {x2} {y2} {ms}')
def type_text(s):
    for c in s: adb('shell','input','text',c, check=False)
    if 'mInputShown=true' in sh('dumpsys input_method | grep mInputShown'):
        sh('input keyevent KEYCODE_BACK'); time.sleep(0.3)
def cap(name, label):
    IMGS.mkdir(exist_ok=True)
    sh(f'screencap -p /sdcard/{name}.png')
    adb('pull', f'/sdcard/{name}.png', str(IMGS/f'{name}.png'), check=False)
    sh(f'rm -f /sdcard/{name}.png')
    print(f'  captured {name}.png  ({label})')
def push_settings():
    xml = '''<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name="mqtt_host">mqtt.sysone.co.za</string><int name="mqtt_port" value="443" /><string name="mqtt_protocol">ws://</string><boolean name="mqtt_use_ssl" value="true" /><boolean name="mqtt_validate_cert" value="true" /><string name="mqtt_username">admin</string><string name="mqtt_password">admin</string><int name="scanner_int" value="1" /><int name="station_int" value="1" /></map>'''
    p = HERE/'_s.xml'; p.write_text(xml)
    adb('push',str(p),'/data/local/tmp/_s.xml',check=False)
    sh(f'run-as {APP} mkdir -p /data/data/{APP}/shared_prefs')
    sh(f'run-as {APP} cp /data/local/tmp/_s.xml /data/data/{APP}/shared_prefs/settings.xml')
    sh('rm -f /data/local/tmp/_s.xml'); p.unlink(missing_ok=True)
    time.sleep(0.5)
def reset_and_launch():
    sh(f'am force-stop {APP}'); sh(f'pm clear {APP}'); push_settings()
    sh('input keyevent KEYCODE_HOME'); time.sleep(1.5)
    tap(153,884); time.sleep(4.5)
    SCENARIO.write_text('success')

import re, json
def dump_ui():
    sh('uiautomator dump /sdcard/u.xml'); tmp=HERE/'_u.xml'
    adb('pull','/sdcard/u.xml',str(tmp),check=False); sh('rm -f /sdcard/u.xml')
    try: return tmp.read_text(encoding='utf-8',errors='replace')
    except: return ''
def wait_for(pats, timeout=12):
    end=time.time()+timeout
    while time.time()<end:
        x=dump_ui()
        for p in pats:
            if re.search(p,x,re.IGNORECASE): return True
        time.sleep(0.5)
    return False
def find_center(xml,pat):
    rx=re.compile(pat,re.IGNORECASE)
    for m in re.finditer(r'<node[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*/?>',xml):
        tag=xml[m.start():xml.find('>',m.start())+1]
        if rx.search(tag):
            x1,y1,x2,y2=map(int,m.groups()); return (x1+x2)//2,(y1+y2)//2
def tap_id(rid,timeout=5):
    end=time.time()+timeout
    while time.time()<end:
        xy=find_center(dump_ui(),re.escape(rid))
        if xy: tap(*xy); return xy
        time.sleep(0.4)

print('=== Targeted recapture ===')

# ─── 1. SAP success popup ───
print('\n[1] SAP lookup success popup')
reset_and_launch()
# wait for dashboard
wait_for([r'tileSapLookup|STEP 1|Lookup SAP'],timeout=10)
tap_id('tileSapLookup',timeout=5) or tap(270,856)
time.sleep(2.5)
wait_for([r'etDocNumber|spinnerDocType'],timeout=8)
tap(540,589); time.sleep(0.4)
type_text(PO); time.sleep(0.3)
# select doctype via dpad
tap_id('spinnerDocType',timeout=3) or tap(540,802)
time.sleep(0.8); sh('input keyevent KEYCODE_DPAD_DOWN'); time.sleep(0.3); sh('input keyevent KEYCODE_ENTER'); time.sleep(0.5)
# tap LOOKUP — mock responds in ~0.5s, popup holds 2s
tap_id('btnSubmit',timeout=3) or tap(540,1042)
time.sleep(0.5)      # loading popup visible at ~0.5s
cap('08_sap_lookup_loading','SAP Lookup loading popup')
# success popup should appear within another 0.5s, sleep 0.3 then cap
time.sleep(0.3)
cap('09_sap_lookup_success','SAP Lookup success popup')
print('  done — waiting for product request screen to confirm timing OK...')
wait_for([r'cbSelect|tvDocNumberDisplay'],timeout=15)
print('  confirmed navigation to product screen')

# ─── 2. Settings scroll ───
print('\n[2] Settings scroll mid + bottom')
sh(f'am force-stop {APP}'); sh(f'pm clear {APP}'); push_settings()
sh('input keyevent KEYCODE_HOME'); time.sleep(1.2)
tap(153,884); time.sleep(4.5)
wait_for([r'tileSapLookup|STEP 1'],timeout=8)
# open settings
tap(772,192); time.sleep(1.2)
if wait_for([r'btnPopupPositive|ACCESS'],timeout=5):
    type_text(PWD); time.sleep(0.4); tap_id('btnPopupPositive',timeout=3)
time.sleep(1.5)
if wait_for([r'MQTT|Scanner ID|Station'],timeout=6):
    cap('03_settings_top','Settings top — re-check')
    swipe(540,1700,540,400,700); time.sleep(1.5)
    cap('04_settings_mid','Settings mid')
    swipe(540,1700,540,400,700); time.sleep(1.5)
    cap('05_settings_bottom','Settings bottom')
    print('  settings screenshots done')

print('\n=== Done ===')
