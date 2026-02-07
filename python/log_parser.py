import re
import csv
import sys
import json
from datetime import datetime

def parse_log_file(log_file, output_csv):
    """
    Parses Flink logs to extract Global MCC and Optimization/Retraining events.
    IMPROVEMENT: Uses the timestamp of the latest LOCAL_REPORT for the Global Report 
    to ensure the X-axis progresses smoothly even if the Window timestamp is static.
    """
    
    # 1. Regex for GLOBAL_REPORT (captures MCCs)
    global_pattern = re.compile(
        r"GLOBAL_REPORT: Report\{ts=(\d+), key='GLOBAL', .*?runtime MCC=([\d\.\-]+)', batch MCC=([\d\.\-]+)\}"
    )

    # 2. Regex for LOCAL_REPORT (captures timestamp only)
    # Example: LOCAL_REPORT: Report{ts=1443877181, key='227318040'...
    local_pattern = re.compile(
        r"LOCAL_REPORT: Report\{ts=(\d+), key="
    )

    data_points = []
    events = []
    
    # State to track the latest "real" time seen from any ship
    latest_local_ts = 0

    skip_first = True

    print(f"Reading log file: {log_file}...")
    
    try:
        with open(log_file, 'r') as f:
            for line in f:
                
                # --- A. Check for LOCAL Report (Update Clock) ---
                local_match = local_pattern.search(line)
                if local_match:
                    # Update our "clock" to the latest event processed by any ship
                    latest_local_ts = int(local_match.group(1))
                    continue

                # --- B. Check for GLOBAL Report (Record Data) ---
                global_match = global_pattern.search(line)
                if global_match:
                    if skip_first:
                        skip_first = False
                        continue
                    # Original TS from log (often window start/end)
                    log_ts = int(global_match.group(1))
                    runtime_mcc = float(global_match.group(2))
                    batch_mcc = float(global_match.group(3))
                    
                    # DECISION: Use latest_local_ts if available, otherwise fallback to log_ts
                    final_ts = latest_local_ts if latest_local_ts > 0 else log_ts

                    data_points.append({
                        "timestamp": final_ts,
                        "runtime_mcc": runtime_mcc,
                        "batch_mcc": batch_mcc,
                        "event": None
                    })
                    continue

                # --- C. Check for Instructions (JSON) ---
                if "INSTRUCTION:" in line:
                    try:
                        json_part = line.split("INSTRUCTION:", 1)[1].strip()
                        data = json.loads(json_part)
                        
                        action = data.get("instruction_type")
                        ts = data.get("timestamp")
                        
                        if action and ts:
                            events.append((int(ts), action))
                            
                    except Exception:
                        pass

    except FileNotFoundError:
        print(f"Error: File {log_file} not found.")
        return

    # Merge events into data points
    print(f"Found {len(data_points)} reports and {len(events)} events.")
    
    for event_ts, action in events:
        closest_point = None
        min_diff = float('inf')
        
        for point in data_points:
            diff = abs(point["timestamp"] - event_ts)
            if diff < min_diff:
                min_diff = diff
                closest_point = point
        
        if closest_point:
            closest_point["event"] = action

    # Sort by timestamp (crucial since we modified them)
    data_points.sort(key=lambda x: x["timestamp"])

    # Write to CSV
    print(f"Writing {len(data_points)} records to {output_csv}...")
    with open(output_csv, 'w', newline='') as csvfile:
        fieldnames = ['timestamp', 'human_time', 'runtime_mcc', 'batch_mcc', 'event']
        writer = csv.DictWriter(csvfile, fieldnames=fieldnames)

        writer.writeheader()
        for point in data_points:
            human_time = datetime.utcfromtimestamp(point["timestamp"]).strftime('%Y-%m-%d %H:%M:%S')
            
            writer.writerow({
                'timestamp': point["timestamp"],
                'human_time': human_time,
                'runtime_mcc': point["runtime_mcc"],
                'batch_mcc': point["batch_mcc"],
                'event': point["event"] if point["event"] else ""
            })

    print("Done.")

if __name__ == "__main__":
    input_log = "inference_job.log"
    output_csv = "metrics.csv"
    
    if len(sys.argv) > 1:
        input_log = sys.argv[1]
    if len(sys.argv) > 2:
        output_csv = sys.argv[2]

    parse_log_file(input_log, output_csv)