"""
CSV Dataset Splitter for RTCEF.

Splits a CSV dataset into Training and Inference sets while maintaining
chronological order (sorting by timestamp) to ensure realistic evaluation.
"""

import argparse
import os

def main():
    """
    Main entry point for the dataset splitter.
    Parses arguments, reads data, sorts by timestamp, and writes split files.
    """
    parser = argparse.ArgumentParser(description='Split CSV dataset into Training and Inference sets')
    parser.add_argument('--file', required=True, help='Path to input CSV file')
    parser.add_argument('--train-pct', type=float, default=0.05, help='Percentage of data to use for training (0.0 to 1.0)')
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
        
    # Check for header (heuristic: starts with non-digit)
    has_header = False
    if lines[0] and not lines[0][0].isdigit():
        header = lines[0]
        data = lines[1:]
        has_header = True
    else:
        header = ""
        data = lines

    # ALWAYS Sort by Timestamp (Column 0) before splitting for chronological order
    print("Sorting Data by Timestamp for chronological split...")
    try:
        data.sort(key=lambda line: int(line.split(',')[0]))
    except Exception as e:
        print(f"Warning: Could not sort by timestamp: {e}")

    if args.shuffle:
        print("WARNING: Shuffling requested but inhibited for chronological safety. Ignoring.")
        
    total_rows = len(data)
    split_idx = int(total_rows * args.train_pct)
    
    train_data = data[:split_idx]
    infer_data = data[split_idx:]
    
    train_file = os.path.join(args.output_dir, 'train.csv')
    infer_file = os.path.join(args.output_dir, 'infer.csv')
    
    print(f"Splitting data: {args.train_pct*100}% Training (Earliest), {(1-args.train_pct)*100}% Inference (Latest)")
    print(f"Total Rows: {total_rows}")
    print(f"Writing {len(train_data)} rows to {train_file}")
    
    with open(train_file, 'w') as f:
        if has_header:
            f.write(header)
        f.writelines(train_data)
        
    print(f"Writing {len(infer_data)} rows to {infer_file}")
    
    with open(infer_file, 'w') as f:
        if has_header:
            f.write(header)
        f.writelines(infer_data)
        
    print("Done.")

if __name__ == "__main__":
    main()
