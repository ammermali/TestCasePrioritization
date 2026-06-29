package it.unicam.tcpimpact;
import it.unicam.tcpimpact.cli.TcpImpactCommand;
import picocli.CommandLine;

/**
 * Entry point of the TCP Impact command-line application.
 * This class initializes the Picocli command and delegates the execution
 * to {@link TcpImpactCommand}. The returned exit code is then used as the
 * process exit status.
 */
public class Main {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new TcpImpactCommand()).execute(args);
        System.exit(exitCode);
    }
}
