package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.file.src.FileSource;
import org.apache.flink.connector.file.src.reader.TextLineInputFormat;
import org.apache.flink.core.fs.Path;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

public class FilePrintJob {

    public static void main(String[] args) throws Exception {
        // 1) Créer l’environnement Flink
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // 2) Résoudre le chemin du fichier d’entrée
        //    - si tu passes un argument en ligne de commande, on l’utilise
        //    - sinon, par défaut: "data/input.txt" (à toi de créer le fichier plus tard)
        String inputPath = args.length > 0 ? args[0] : "../data/input.txt";

        // 3) Définir une source fichier "ligne par ligne"
        FileSource<String> source =
                FileSource
                        .forRecordStreamFormat(new TextLineInputFormat(), new Path(inputPath))
                        .build();

        // 4) Créer un DataStream à partir de cette source (pas de watermarks pour le moment)
        DataStream<String> lines = env.fromSource(
                source,
                WatermarkStrategy.noWatermarks(),
                "file-source"
        );

        // 5) Envoyer chaque ligne sur la sortie standard
        lines.print();

        // 6) Lancer le job
        env.execute("FilePrintJob");
    }
}