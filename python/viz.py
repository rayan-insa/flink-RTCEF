import sys
import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.dates as mdates

def plot_mcc(csv_file, output_image):
    # 1. Load Data
    try:
        df = pd.read_csv(csv_file)
    except FileNotFoundError:
        print(f"Error: {csv_file} not found.")
        return

    # 2. Prepare Data
    df['human_time'] = pd.to_datetime(df['human_time'])
    df = df.sort_values('human_time')

    # 3. Setup Plot
    plt.figure(figsize=(14, 7))
    
    plt.plot(df['human_time'], df['batch_mcc'], 
             label='Batch MCC (Instant)', color='#1f77b4', alpha=0.5, linewidth=1)

    plt.plot(df['human_time'], df['runtime_mcc'], 
             label='Runtime MCC (Cumulative)', color='#ff7f0e', linewidth=2.5)

    # 4. Add Event Markers (Optimize/Retrain)
    events = df[df['event'].notna() & (df['event'] != '')]
    
    for _, row in events.iterrows():
        event_time = row['human_time']
        event_type = str(row['event']).upper()
        
        # Style based on event type
        color = 'green' if 'OPTIMIZE' in event_type else 'red'
        
        # Draw Vertical Line
        plt.axvline(x=event_time, color=color, linestyle='--', linewidth=1.5, alpha=0.8)
        
        # Add Label at the top
        plt.text(event_time, 1.02, event_type, 
                 transform=plt.gca().get_xaxis_transform(),
                 rotation=0, ha='center', va='bottom', 
                 color='white', backgroundcolor=color, fontsize=9, fontweight='bold')

    # 5. Formatting
    plt.title('Model Performance Monitoring: Batch vs. Runtime MCC', fontsize=14)
    plt.ylabel('Matthews Correlation Coefficient (MCC)', fontsize=12)
    plt.xlabel('Time', fontsize=12)
    plt.ylim(-0.1, 1.1)  # MCC range is -1 to 1, but we usually care about 0 to 1
    
    # Grid and Legend
    plt.grid(True, linestyle=':', alpha=0.6)
    plt.legend(loc='lower right', frameon=True, shadow=True)

    # Format Date Axis
    plt.gca().xaxis.set_major_formatter(mdates.DateFormatter('%H:%M:%S'))
    plt.gcf().autofmt_xdate()

    # 6. Save
    plt.tight_layout()
    plt.savefig(output_image, dpi=300)
    print(f"Plot saved to {output_image}")
    plt.show()

if __name__ == "__main__":

    if len(sys.argv) > 2:
        input_log = sys.argv[1]
        output_png = sys.argv[2]
    else:
        raise NotImplementedError()
    plot_mcc(input_log, output_png) 