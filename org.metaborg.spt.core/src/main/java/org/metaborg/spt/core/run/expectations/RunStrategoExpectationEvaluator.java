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
import org.metaborg.mbt.core.model.expectations.RunStrategoExpectation;
import org.metaborg.mbt.core.run.ITestExpectationInput;
import org.metaborg.spoofax.core.stratego.IStrategoRuntimeService;
import org.metaborg.spoofax.core.stratego.StrategoRuntimeFacet;
import org.metaborg.spoofax.core.terms.ITermFactoryService;
import org.metaborg.spoofax.core.tracing.ISpoofaxTracingService;
import org.metaborg.spoofax.core.unit.ISpoofaxAnalyzeUnit;
import org.metaborg.spoofax.core.unit.ISpoofaxParseUnit;
import org.metaborg.spt.core.run.FragmentUtil;
import org.metaborg.spt.core.run.ISpoofaxExpectationEvaluator;
import org.metaborg.spt.core.run.ISpoofaxFragmentResult;
import org.metaborg.spt.core.run.ISpoofaxTestExpectationOutput;
import org.metaborg.spt.core.run.SpoofaxFragmentResult;
import org.metaborg.spt.core.run.SpoofaxTestExpectationOutput;
import org.metaborg.util.log.ILogger;
import org.metaborg.util.log.LoggerUtils;
import org.spoofax.interpreter.core.InterpreterException;
import org.spoofax.interpreter.core.UndefinedStrategyException;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.strategoxt.HybridInterpreter;
import org.strategoxt.lang.TermEqualityUtil;

import com.google.common.collect.Lists;
import com.google.inject.Inject;

public class RunStrategoExpectationEvaluator implements ISpoofaxExpectationEvaluator<RunStrategoExpectation> {

    private static final ILogger logger = LoggerUtils.logger(RunStrategoExpectationEvaluator.class);

    private final IStrategoRuntimeService runtimeService;
    private final ISpoofaxTracingService traceService;
    private final ITermFactoryService termFactoryService;

    private final FragmentUtil fragmentUtil;

    @Inject public RunStrategoExpectationEvaluator(IStrategoRuntimeService runtimeService,
        ISpoofaxTracingService traceService, ITermFactoryService termFactoryService, FragmentUtil fragmentUtil) {
        this.runtimeService = runtimeService;
        this.traceService = traceService;
        this.termFactoryService = termFactoryService;

        this.fragmentUtil = fragmentUtil;
    }

    @Override public Collection<Integer> usesSelections(IFragment fragment, RunStrategoExpectation expectation) {
        List<Integer> used = Lists.newLinkedList();
        // we use the first selection (if any)
        if(!fragment.getSelections().isEmpty()) {
            used.add(0);
        }
        return used;
    }

    @Override public TestPhase getPhase(IContext languageUnderTestCtx, RunStrategoExpectation expectation) {
        // until we support running on raw ASTs
        return TestPhase.ANALYSIS;
    }

    @Override public ISpoofaxTestExpectationOutput evaluate(
        ITestExpectationInput<ISpoofaxParseUnit, ISpoofaxAnalyzeUnit> input, RunStrategoExpectation expectation) {

        logger.debug("Evaluating a RunStrategoExpectation (strat: {}, outputLang: {}, outputFragment: {})",
            expectation.strategy(), expectation.outputLanguage(), expectation.outputFragment());

        List<IMessage> messages = Lists.newLinkedList();
        List<ISpoofaxFragmentResult> fragmentResults = Lists.newLinkedList();

        ITestCase test = input.getTestCase();
        List<ISourceRegion> selections = test.getFragment().getSelections();

        String strategy = expectation.strategy();

        // we need an analysis result with an AST (until we allow running on raw ASTs)
        ISpoofaxAnalyzeUnit analysisResult = input.getFragmentResult().getAnalysisResult();
        if(analysisResult == null) {
            messages.add(MessageFactory.newAnalysisError(test.getResource(), test.getDescriptionRegion(),
                "Expected analysis to succeed", null));
            return new SpoofaxTestExpectationOutput(false, messages, fragmentResults);
        }
        if(!analysisResult.valid() || !analysisResult.hasAst()) {
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
            messages.add(MessageFactory.newAnalysisError(test.getResource(), test.getDescriptionRegion(),
                "Unable to load the StrategoRuntimeFacet for the language under test.", null));
        } else {
            try {
                runtime = runtimeService.runtime(facetContrib.contributor, analysisResult.context(), false);
            } catch(MetaborgException e) {
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
            messages.add(MessageFactory.newAnalysisError(test.getResource(), test.getDescriptionRegion(),
                "Too many selections in this test case, we don't know which selection you want to use as input.",
                null));
        } else {
            // the input should be the selected term
            ISourceRegion selection = selections.get(0);
            for(IStrategoTerm possibleSelection : traceService.fragments(analysisResult, selection)) {
                ISourceLocation loc = traceService.location(possibleSelection);
                // the region should match exactly
                if(loc != null && loc.region().startOffset() == selection.startOffset()
                    && loc.region().endOffset() == selection.endOffset()) {
                    terms.add(possibleSelection);
                }
            }
            if(terms.isEmpty()) {
                messages.add(MessageFactory.newAnalysisError(test.getResource(), selection,
                    "Could not resolve this selection to an AST node.", null));
            }
        }
        terms = Lists.reverse(terms);

        // before we try to run anything, make sure we have a runtime and something to execute on
        if(runtime == null || terms.isEmpty()) {
            return new SpoofaxTestExpectationOutput(false, messages, fragmentResults);
        }

        // run the strategy until we are done
        boolean success = false;
        IMessage lastMessage = null;
        for(IStrategoTerm term : terms) {
            // logger.debug("About to try to run the strategy {} on {}", expectation.strategy(), term);
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
                // the strategy was successful
                if(expectation.outputFragment() == null) {
                    // a successful invocation is all we need
                    success = true;
                } else {
                    // it's a RunTo(strategyName, ToPart(languageName, openMarker, fragment, closeMarker))
                    // we need to analyze the fragment, at least until we support running on raw parsed terms
                    ISpoofaxAnalyzeUnit analyzedFragment = fragmentUtil.analyzeFragment(expectation.outputFragment(),
                        expectation.outputLanguage(), messages, test, input.getFragmentParserConfig());
                    // compare the ASTs
                    if(analyzedFragment != null
                        && TermEqualityUtil.equalsIgnoreAnnos(analyzedFragment.ast(), runtime.current(),
                            termFactoryService.get(
                                fragmentUtil.getLanguage(expectation.outputLanguage(), messages, test).activeImpl(),
                                test.getProject(), false))) {
                        success = true;
                    } else {
                        lastMessage = MessageFactory.newAnalysisError(test.getResource(), test.getDescriptionRegion(),
                            String.format(
                                "The result of running %1$s did not match the expected result.\nExpected: %2$s\nGot: %3$s",
                                strategy, analyzedFragment == null ? "null" : analyzedFragment.ast(),
                                runtime.current()),
                            null);
                    }
                    fragmentResults.add(new SpoofaxFragmentResult(expectation.outputFragment(),
                        analyzedFragment.input(), analyzedFragment, null));
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
