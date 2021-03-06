package org.metaborg.spt.core.run.expectations;

import java.util.Collection;
import java.util.List;

import org.metaborg.core.MetaborgException;
import org.metaborg.core.context.IContext;
import org.metaborg.core.language.FacetContribution;
import org.metaborg.core.messages.IMessage;
import org.metaborg.core.messages.MessageFactory;
import org.metaborg.core.source.ISourceLocation;
import org.metaborg.core.source.ISourceRegion;
import org.metaborg.mbt.core.model.IFragment;
import org.metaborg.mbt.core.model.ITestCase;
import org.metaborg.mbt.core.model.TestPhase;
import org.metaborg.mbt.core.model.expectations.MessageUtil;
import org.metaborg.mbt.core.run.ITestExpectationInput;
import org.metaborg.spoofax.core.stratego.IStrategoRuntimeService;
import org.metaborg.spoofax.core.stratego.StrategoRuntimeFacet;
import org.metaborg.spoofax.core.terms.ITermFactoryService;
import org.metaborg.spoofax.core.tracing.ISpoofaxTracingService;
import org.metaborg.spoofax.core.unit.ISpoofaxAnalyzeUnit;
import org.metaborg.spoofax.core.unit.ISpoofaxParseUnit;
import org.metaborg.spt.core.expectations.RunStrategoToAtermExpectation;
import org.metaborg.spt.core.run.ISpoofaxExpectationEvaluator;
import org.metaborg.spt.core.run.ISpoofaxFragmentResult;
import org.metaborg.spt.core.run.ISpoofaxTestExpectationOutput;
import org.metaborg.spt.core.run.SpoofaxTestExpectationOutput;
import org.metaborg.util.iterators.Iterables2;
import org.metaborg.util.log.ILogger;
import org.metaborg.util.log.LoggerUtils;
import org.spoofax.interpreter.core.InterpreterException;
import org.spoofax.interpreter.core.UndefinedStrategyException;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.strategoxt.HybridInterpreter;
import org.strategoxt.lang.TermEqualityUtil;

import com.google.common.collect.Lists;
import com.google.inject.Inject;

public class RunStrategoToAtermExpectationEvaluator
    implements ISpoofaxExpectationEvaluator<RunStrategoToAtermExpectation> {

    private static final ILogger logger = LoggerUtils.logger(RunStrategoToAtermExpectationEvaluator.class);

    private final IStrategoRuntimeService runtimeService;
    private final ISpoofaxTracingService traceService;
    private final ITermFactoryService termFactoryService;

    @Inject public RunStrategoToAtermExpectationEvaluator(IStrategoRuntimeService runtimeService,
        ISpoofaxTracingService traceService, ITermFactoryService termFactoryService) {
        this.runtimeService = runtimeService;
        this.traceService = traceService;
        this.termFactoryService = termFactoryService;
    }

    @Override public Collection<Integer> usesSelections(IFragment fragment, RunStrategoToAtermExpectation expectation) {
        List<Integer> used = Lists.newLinkedList();
        // we use the first selection (if any)
        if(!fragment.getSelections().isEmpty()) {
            used.add(0);
        }
        return used;
    }

    @Override public TestPhase getPhase(IContext languageUnderTestCtx, RunStrategoToAtermExpectation expectation) {
        // until we support running on raw ASTs
        return TestPhase.ANALYSIS;
    }

    @Override public ISpoofaxTestExpectationOutput evaluate(
        ITestExpectationInput<ISpoofaxParseUnit, ISpoofaxAnalyzeUnit> input,
        RunStrategoToAtermExpectation expectation) {

        List<IMessage> messages = Lists.newLinkedList();
        // the 'to ATerm' variant of this expectation doesn't have a fragment
        Iterable<ISpoofaxFragmentResult> fragmentResults = Iterables2.empty();

        ITestCase test = input.getTestCase();
        List<ISourceRegion> selections = test.getFragment().getSelections();

        String strategy = expectation.strategy();

        // we need an analysis result with an AST (until we allow running on raw ASTs)
        ISpoofaxAnalyzeUnit analysisResult = input.getFragmentResult().getAnalysisResult();
        if(analysisResult == null) {
            logger.debug("Expected analysis to succeed");
            messages.add(MessageFactory.newAnalysisError(test.getResource(), test.getDescriptionRegion(),
                "Expected analysis to succeed", null));
            return new SpoofaxTestExpectationOutput(false, messages, fragmentResults);
        }
        if(!analysisResult.valid() || !analysisResult.hasAst()) {
            logger.debug("Analysis did not return a valid AST.");
            messages.add(MessageFactory.newAnalysisError(test.getResource(), test.getDescriptionRegion(),
                "Analysis did not return a valid AST.", null));
            MessageUtil.propagateMessages(analysisResult.messages(), messages, test.getDescriptionRegion(),
                test.getFragment().getRegion());
            return new SpoofaxTestExpectationOutput(false, messages, fragmentResults);
        }

        // Create the runtime for stratego
        HybridInterpreter runtime = null;
        FacetContribution<StrategoRuntimeFacet> facetContrib =
            input.getLanguageUnderTest().facetContribution(StrategoRuntimeFacet.class);
        if(facetContrib == null) {
            logger.debug("Unable to load the StrategoRuntimeFacet for the language under test.");
            messages.add(MessageFactory.newAnalysisError(test.getResource(), test.getDescriptionRegion(),
                "Unable to load the StrategoRuntimeFacet for the language under test.", null));
        } else {
            try {
                runtime = runtimeService.runtime(facetContrib.contributor, analysisResult.context(), false);
                if(runtime == null) {
                    logger.debug("Unable to create a runtime! This should NOT happen, it isn't Nullable.");
                }
            } catch(MetaborgException e) {
                logger.debug("Unable to load required files for the Stratego runtime.");
                messages.add(MessageFactory.newAnalysisError(test.getResource(), test.getDescriptionRegion(),
                    "Unable to load required files for the Stratego runtime.", e));
            }
        }

        /*
         * Obtain the AST nodes to try to run on.
         * 
         * We collect all terms with the exact right offsets, and try to execute the strategy on each of these terms,
         * starting on the outermost term, until we processed them all or one of them passed successfully.
         */
        List<IStrategoTerm> terms = Lists.newLinkedList();
        if(selections.isEmpty()) {
            // no selections, so we run on the entire ast
            terms.add(analysisResult.ast());
        } else if(selections.size() > 1) {
            // too many selections, we don't know which to select as input
            logger.debug(
                "Too many selections in this test case, we don't know which selection you want to use as input.");
            messages.add(MessageFactory.newAnalysisError(test.getResource(), test.getDescriptionRegion(),
                "Too many selections in this test case, we don't know which selection you want to use as input.",
                null));
        } else {
            // the input should be the selected term
            ISourceRegion selection = selections.get(0);
            for(IStrategoTerm possibleSelection : traceService.fragments(analysisResult, selection)) {
                ISourceLocation loc = traceService.location(possibleSelection);
                logger.debug("Checking possible selected term {} with location {}", possibleSelection, loc);
                // the region should match exactly
                if(loc != null && loc.region().startOffset() == selection.startOffset()
                    && loc.region().endOffset() == selection.endOffset()) {
                    logger.debug("Matched, adding it as input node to the strategy");
                    terms.add(possibleSelection);
                }
            }
            if(terms.isEmpty()) {
                logger.debug("Could not resolve this selection to an AST node.");
                messages.add(MessageFactory.newAnalysisError(test.getResource(), selection,
                    "Could not resolve this selection to an AST node.", null));
            }
        }
        terms = Lists.reverse(terms);

        // before we try to run anything, make sure we have a runtime and something to execute on
        if(runtime == null || terms.isEmpty()) {
            logger.debug("Returning early, as there is either no runtime or nothing to run on.");
            return new SpoofaxTestExpectationOutput(false, messages, fragmentResults);
        }

        // run the strategy until we are done
        boolean success = false;
        IMessage lastMessage = null;
        for(IStrategoTerm term : terms) {
            // reset the last message
            lastMessage = null;
            runtime.setCurrent(term);
            try {
                // if the strategy failed, try the next input term
                if(!runtime.invoke(strategy)) {
                    lastMessage = MessageFactory.newAnalysisError(test.getResource(), test.getDescriptionRegion(),
                        String.format("The given strategy %1$s failed during execution.", expectation.strategy()),
                        null);
                    continue;
                }
                // the strategy was successfull
                // compare the ASTs
                if(TermEqualityUtil.equalsIgnoreAnnos(expectation.expectedResult(), runtime.current(),
                    termFactoryService.get(input.getLanguageUnderTest(), test.getProject(), false))) {
                    success = true;
                } else {
                    lastMessage = MessageFactory.newAnalysisError(test.getResource(), test.getDescriptionRegion(),
                        String.format(
                            "The result of running %1$s did not match the expected result.\nExpected: %2$s\nGot: %3$s",
                            strategy, expectation.expectedResult(), runtime.current()),
                        null);
                }
                if(success) {
                    break;
                }
            } catch(UndefinedStrategyException e) {
                lastMessage = MessageFactory.newAnalysisError(test.getResource(), expectation.strategyRegion(),
                    "No such strategy found: " + strategy, e);
                // this exception does not depend on the input so we can stop trying
                break;
            } catch(InterpreterException e) {
                // who knows what caused this, but we will just keep trying on the other terms
                lastMessage = MessageFactory.newAnalysisError(test.getResource(), test.getDescriptionRegion(),
                    "Encountered an error while executing the given strategy.", e);
            }
        }
        if(lastMessage != null) {
            messages.add(lastMessage);
        }

        return new SpoofaxTestExpectationOutput(success, messages, fragmentResults);
    }

}
