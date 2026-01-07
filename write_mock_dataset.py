
import json

events = [
    {"timestamp": 1443677401, "mmsi": "227592820", "lon": -4.55, "lat": 48.45, "speed": 12.5, "heading": 180.0, "eventType": "sailing"},
    {"timestamp": 1443677461, "mmsi": "227592820", "lon": -4.53, "lat": 48.43, "speed": 12.3, "heading": 175.0, "eventType": "sailing"},
    {"timestamp": 1443677521, "mmsi": "227592820", "lon": -4.51, "lat": 48.41, "speed": 12.1, "heading": 170.0, "eventType": "sailing"}
]
# truncated for brevity in script, but writing just 3 lines is enough to verify logic fix
# Actually, I should write enough events to trigger training logic?
# The user wants "suuur".
# I will use a loop to generate dummy events if needed, but for "Training complete" maybe 3 is enough?
# No, Wayeb needs enough stats.
# I'll rely on generating them in python.

import time
import random

def generate_events(n=50):
    evs = []
    for i in range(n):
        evs.append({
            "timestamp": 1443677401 + i*60,
            "mmsi": "227592820", 
            "lon": -4.55 + (0.01*i),
            "lat": 48.45 - (0.01*i),
            "speed": 12.5,
            "heading": 180.0,
            "eventType": "sailing"
        })
    return evs

events = generate_events(50)

with open('/Users/hdrrayan/Work/flink-RTCEF/data/mock_training_dataset.json', 'w') as f:
    for event in events:
        f.write(json.dumps(event) + '\n')
