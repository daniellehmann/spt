package org.metaborg.spt.cmd;

import org.metaborg.spoofax.core.Spoofax;
import org.metaborg.spt.core.SPTModule;
import org.metaborg.util.log.ILogger;
import org.metaborg.util.log.LoggerUtils;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import com.google.inject.Injector;

public class Main {
    private static final ILogger logger = LoggerUtils.logger(Main.class);


    public static void main(String[] args) {
        final Arguments arguments = new Arguments();
        final JCommander jc = new JCommander(arguments);

        try {
            jc.parse(args);
        } catch(ParameterException e) {
            logger.error("Could not parse parameters", e);
            jc.usage();
            System.exit(1);
        }

        if(arguments.help) {
            jc.usage();
            System.exit(0);
        }

        if(arguments.exit) {
            logger.info("Exitting immediately for testing purposes");
            System.exit(0);
        }

        final Module module = new Module();
        try(final Spoofax spoofax = new Spoofax(module, new SPTModule())) {

            final Injector injector = spoofax.injector;

            final Runner runner = injector.getInstance(Runner.class);

            runner.run(arguments.sptLocation, arguments.lutLocations, arguments.targetLanguageLocation,
                arguments.testsLocation, arguments.startSymbol);

            System.exit(0);

        } catch(Exception e) {
            logger.error("Error while running tests", e);
            System.exit(1);
        }
    }
}
