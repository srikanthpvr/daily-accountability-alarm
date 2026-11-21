"""
Verification Script for Daily Accountability App Core Logic:
1. Transcript Parser (Turn-by-turn spoken responses for Option B)
2. Alarm Trigger Calculation (9 PM daily boundary conditions)
3. Google Sheets REST API v4 Payload Contract Verification
"""

import re
from datetime import datetime, time, timedelta

AFFIRMATIVE_KEYWORDS = {
    "yes", "yeah", "yep", "yup", "aye", "sure", "done", "completed", "did",
    "finished", "already", "of course", "affirmative", "all set", "checked",
    "soaked", "hung", "packed", "absolutely", "i did"
}

NEGATIVE_KEYWORDS = {
    "no", "nope", "nah", "not", "haven't", "have not", "didn't", "did not",
    "missed", "skipped", "forgot", "later", "negative", "never", "uncompleted",
    "havent", "didnt"
}

def parse_turn_response(spoken_text: str) -> str:
    if not spoken_text or not spoken_text.strip():
        return "UNCLEAR"

    normalized = re.sub(r"[^a-z0-9\s]", " ", spoken_text.lower()).strip()
    tokens = normalized.split()

    has_negative = any(tok in NEGATIVE_KEYWORDS or tok in ["havent", "didnt", "not"] for tok in tokens) or \
        any(phrase in normalized for phrase in ["not yet", "did not", "have not", "haven t", "didn t", "not done"])

    has_affirmative = any(tok in AFFIRMATIVE_KEYWORDS for tok in tokens) or \
        any(phrase in normalized for phrase in ["i did", "all set", "already done"])

    if has_negative:
        # Negation takes precedence over completion verbs (e.g., "haven't done", "not completed", "didn't do")
        return "NO"
    elif has_affirmative:
        return "YES"
    else:
        return "UNCLEAR"

def calculate_next_9pm(now: datetime) -> datetime:
    target = now.replace(hour=21, minute=0, second=0, microsecond=0)
    if target <= now:
        target += timedelta(days=1)
    return target

def build_google_sheets_payload(spreadsheet_id: str, sheet_name: str, timestamp: str, socks: str, towel: str, books: str, transcript: str):
    range_str = f"{sheet_name}!A:F"
    return {
        "url": f"https://sheets.googleapis.com/v4/spreadsheets/{spreadsheet_id}/values/{range_str}:append?valueInputOption=USER_ENTERED",
        "body": {
            "range": range_str,
            "majorDimension": "ROWS",
            "values": [
                [timestamp, socks, towel, books, transcript, "SUCCESS"]
            ]
        }
    }

def run_tests():
    print("=== TEST SUITE: Daily Accountability App Logic Verification ===")

    # 1. Test Spoken Affirmative parsing
    affirmative_samples = [
        "yes", "yeah", "yep", "done", "completed", "i did it", "already soaked them", "yes I did", "all set"
    ]
    for sample in affirmative_samples:
        res = parse_turn_response(sample)
        assert res == "YES", f"Expected YES for '{sample}', got {res}"
    print(f"✓ All {len(affirmative_samples)} affirmative speech tests PASSED")

    # 2. Test Spoken Negative parsing
    negative_samples = [
        "no", "nope", "not yet", "haven't done it", "didn't get time", "no I didn't", "skipped", "forgot"
    ]
    for sample in negative_samples:
        res = parse_turn_response(sample)
        assert res == "NO", f"Expected NO for '{sample}', got {res}"
    print(f"✓ All {len(negative_samples)} negative speech tests PASSED")

    # 3. Test Edge cases
    assert parse_turn_response("") == "UNCLEAR"
    assert parse_turn_response("   ") == "UNCLEAR"
    assert parse_turn_response("maybe tomorrow") == "UNCLEAR"
    print("✓ Edge cases (silence / unclear) PASSED")

    # 4. Test 9 PM Alarm calculations
    now_before_9pm = datetime(2026, 11, 21, 14, 30, 0)
    next_alarm = calculate_next_9pm(now_before_9pm)
    assert next_alarm == datetime(2026, 11, 21, 21, 0, 0), f"Expected today 21:00, got {next_alarm}"

    now_after_9pm = datetime(2026, 11, 21, 21, 15, 0)
    next_alarm_tmrw = calculate_next_9pm(now_after_9pm)
    assert next_alarm_tmrw == datetime(2026, 11, 22, 21, 0, 0), f"Expected tomorrow 21:00, got {next_alarm_tmrw}"
    print("✓ AlarmManager 9:00 PM boundary tests PASSED")

    # 5. Test Google Sheets Payload Structure
    payload = build_google_sheets_payload(
        spreadsheet_id="test_sheet_123",
        sheet_name="Accountability_Log",
        timestamp="2026-11-21 21:00:00",
        socks="YES",
        towel="NO",
        books="YES",
        transcript="Soaking Socks: yes | Hanging Towel: no | Packing Books: yes"
    )
    assert "values" in payload["body"]
    row = payload["body"]["values"][0]
    assert row == ["2026-11-21 21:00:00", "YES", "NO", "YES", "Soaking Socks: yes | Hanging Towel: no | Packing Books: yes", "SUCCESS"]
    assert "test_sheet_123" in payload["url"]
    print("✓ Google Sheets v4 API payload contract PASSED")

    print("\nALL VERIFICATION TESTS COMPLETED SUCCESSFULLY!")

if __name__ == "__main__":
    run_tests()
