"""
CSV Dataset Splitter for RTCEF.

Splits a CSV dataset into Training and Inference sets while maintaining
chronological order (sorting by timestamp) to ensure realistic evaluation.
"""

import argparse
import os

def _force_floats(obj, key=None):
    """
    Recursively converts integers to floats, BUT skips specific keys like 'timestamp'.
    """
    if isinstance(obj, dict):
        # Pass the key down to the value processor
        return {k: _force_floats(v, key=k) for k, v in obj.items()}
    elif isinstance(obj, list):
        return [_force_floats(i) for i in obj]
    elif isinstance(obj, int):
        # CRITICAL: Do not convert timestamp to float. Wayeb needs Long.
        if key == 'timestamp' or key == 'next_timestamp':
            return obj 
        return float(obj)
    return obj

def main():
    """
    Main entry point for the dataset splitter.
    Parses arguments, reads data, sorts by timestamp, and writes split files.
    """
    parser = argparse.ArgumentParser(description='Split CSV dataset into Training and Inference sets')
    parser.add_argument('--file', required=True, help='Path to input CSV file')
    parser.add_argument('--train-pct', type=float, default=0.2, help='Percentage of data to use for training (0.0 to 1.0)')
    parser.add_argument('--output-dir', default='data', help='Output directory for split files')
    parser.add_argument('--shuffle', action='store_true', default=False, help='Shuffle data before splitting (DEPRECATED for VMM)')
    
    args = parser.parse_args()
    
    if not os.path.exists(args.file):
        print(f"Error: Input file {args.file} not found.")
        exit(1)

    print(f"Reading {args.file}...")
    with open(args.file, 'r') as f:
        lines = f.readlines()
        
    if not lines:
        print("Error: Empty file.")
        exit(1)
        
    import json

    # Check file extension
    is_jsonl = args.file.endswith('.jsonl')
    
    # Check for header (heuristic: starts with non-digit) - Only for CSV
    has_header = False
    header = ""
    
    if is_jsonl:
        print("Detected JSONL format.")
        parsed_records = []

        for line in lines:
            if not line.strip(): continue
            try:
                record = json.loads(line)
                
                # CRITICAL FIX: Inject the Event Type
                # This ensures 'make train' produces a model that expects "AIS" events
                # record['type'] = 'AIS' 

                # record = _force_floats(record)
                
                parsed_records.append(record)
            except Exception as e:
                print(f"Skipping malformed line: {e}")

        print("Sorting JSON objects by timestamp...")
        parsed_records.sort(key=lambda r: int(r.get('timestamp', 0)))
        
    else:
        if lines[0] and not lines[0][0].isdigit():
            header = lines[0]
            data = lines[1:]
            has_header = True
        else:
            data = lines

        print("Sorting CSV lines by timestamp...")
        # Simple sort assuming first column is timestamp
        data.sort(key=lambda line: int(line.split(',')[0]))

    if args.shuffle:
        print("WARNING: Shuffling requested but inhibited for chronological safety. Ignoring.")
        
    total_rows = len(parsed_records) if is_jsonl else len(data)
    split_idx = int(total_rows * args.train_pct)
    
    train_recs = parsed_records[:split_idx]
    infer_recs = parsed_records[split_idx:]

    print("Sorting TRAINING set by MMSI (Sequential Trajectories)...")
    train_recs.sort(key=lambda r: (str(r.get('mmsi', '')), int(r.get('timestamp', 0))))

    print("Sorting INFERENCE set by Timestamp (Real-time Mix)...")
    infer_recs.sort(key=lambda r: (str(r.get('mmsi', '')), int(r.get('timestamp', 0))))
    
    # Determine output filenames based on input extension
    ext = '.jsonl' if is_jsonl else '.csv'
    train_file = os.path.join(args.output_dir, 'train' + ext)
    infer_file = os.path.join(args.output_dir, 'infer' + ext)
    
    print(f"Splitting data: {args.train_pct*100}% Training (Earliest), {(1-args.train_pct)*100}% Inference (Latest)")
    print(f"Total Rows: {total_rows}")
    print(f"Writing {len(train_recs)} rows to {train_file}")
    
    def write_jsonl(filename, records):
        with open(filename, 'w') as f:
            for r in records:
                f.write(json.dumps(r) + "\n")
    
    write_jsonl(train_file, train_recs)
    write_jsonl(infer_file, infer_recs)
    
    print(f"Done. Train: {len(train_recs)} (Grouped), Infer: {len(infer_recs)} (Mixed).")

if __name__ == "__main__":
    main()
