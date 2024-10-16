package org.globsframework.graphql.parser;

import org.antlr.v4.runtime.tree.TerminalNode;
import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.graphql.GQLGlobType;
import org.globsframework.graphql.parser.antlr.GraphqlBaseVisitor;
import org.globsframework.graphql.parser.antlr.GraphqlParser;
import org.globsframework.json.GSonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

public class AntlrGQLVisitor extends GraphqlBaseVisitor<AntlrGQLVisitor> {
    private static final Logger LOGGER = LoggerFactory.getLogger(AntlrGQLVisitor.class);
    private GlobType schemaType;
    private GlobModel paramTypes;
    private final Map<String, String> variables;
    private Map<String, GraphqlParser.SelectionSetContext> fragments;
    private final Deque<GqlGlobBuilder> trees = new ArrayDeque<>();
    private GQLGlobType gqlGlobType;
    private String operation;

    public AntlrGQLVisitor(GlobType schemaType, GlobModel paramTypes, Map<String, String> variables, Map<String, GraphqlParser.SelectionSetContext> fragments) {
        this.schemaType = schemaType;
        this.paramTypes = paramTypes;
        this.variables = variables;
        this.fragments = fragments;
    }

    public GQLGlobType complete() {
        trees.element().complete();
        return gqlGlobType;
    }

    public AntlrGQLVisitor visitOperationType(GraphqlParser.OperationTypeContext ctx) {
        final ExtractValue visitor = new ExtractValue(variables);
        ctx.accept(visitor);
        operation = visitor.value;
        return this;
    }

    public static class State {
        final boolean isObject;
        final boolean isArray;
        final boolean isArgument;
        final boolean isAttribut;
        boolean isFirst = true;
        boolean isAValue;

        public State() {
            isObject = false;
            isArray = false;
            isArgument = false;
            isAttribut = false;
        }

        public State(boolean isObject, boolean isArray, boolean isArgument,
                     boolean isAttribut, boolean isAValue) {
            this.isObject = isObject;
            this.isArray = isArray;
            this.isArgument = isArgument;
            this.isAttribut = isAttribut;
            this.isAValue = isAValue;
        }

        public State onArray() {
            return new State(false, true, false, false, true);
        }

        public State onObject() {
            return new State(true, false, false, false, false);
        }

        public State onBase() {
            return new State(isObject, isArray, isArgument, true, false);
        }

        public State onValue() {
            return new State(isObject, isArray, isArgument, false, true);
        }

        public State onArgument() {
            return new State(false, false, true, false, false);
        }
    }

    public static class MissingVariable extends RuntimeException {
    }


    public static class JsonBuilder extends GraphqlBaseVisitor<JsonBuilder> {
        Deque<State> states = new ArrayDeque<>();
        final Map<String, String> variables;
        final StringBuilder stringBuilder;

        public JsonBuilder(Map<String, String> variables, StringBuilder stringBuilder) {
            this.variables = variables;
            this.stringBuilder = stringBuilder;
            states.push(new State());
        }

        public JsonBuilder visitArguments(GraphqlParser.ArgumentsContext ctx) {
            states.push(new State());
            stringBuilder.append("{");
            final JsonBuilder jsonBuilder = super.visitArguments(ctx);
            stringBuilder.append("}");
            states.pop();
            return jsonBuilder;
        }

        public JsonBuilder visitArgument(GraphqlParser.ArgumentContext ctx) {
            boolean previous = states.element().isFirst;
            int len = stringBuilder.length();
            if (!states.element().isFirst) {
                stringBuilder.append(",");
            }
            states.element().isFirst = false;
            final JsonBuilder jsonBuilder;
            try {
                states.push(states.element().onArgument());
                jsonBuilder = super.visitArgument(ctx);
                states.pop();
                return jsonBuilder;
            } catch (MissingVariable e) {
                stringBuilder.delete(len, stringBuilder.length());
                states.pop();
                states.element().isFirst = previous;
            }
            return this;
        }

        public JsonBuilder visitVariable(GraphqlParser.VariableContext ctx) {
            ExtractName extractName = new ExtractName();
            extractName.visitVariable(ctx);
            if (!variables.containsKey(extractName.name)) {
                final String msg = "No value for variable " + extractName.name + " legal in graphql but not managed here.";
                LOGGER.warn(msg);
                throw new MissingVariable();
            }
            stringBuilder.append(variables.get(extractName.name));
            return this;
        }

        public JsonBuilder visitValueWithVariable(GraphqlParser.ValueWithVariableContext ctx) {
            boolean previous = states.element().isFirst;
            int len = stringBuilder.length();
            if (!states.element().isFirst) {
                stringBuilder.append(",");
            }
            states.element().isFirst = false;
            states.push(states.element().onValue());
            try {
                super.visitValueWithVariable(ctx);
                states.pop();
            } catch (MissingVariable e) {
                stringBuilder.delete(len, stringBuilder.length());
                states.pop();
                states.element().isFirst = previous;
                if (states.element().isArray) {
                    return this;
                }
                throw e;
            }
            return this;
        }

        public JsonBuilder visitArrayValueWithVariable(GraphqlParser.ArrayValueWithVariableContext ctx) {
            states.push(states.element().onArray());
            super.visitArrayValueWithVariable(ctx);
            states.pop();
            return this;
        }

        @Override
        public JsonBuilder visitObjectFieldWithVariable(GraphqlParser.ObjectFieldWithVariableContext ctx) {
            boolean previous = states.element().isFirst;
            int len = stringBuilder.length();
            if (!states.element().isFirst) {
                stringBuilder.append(",");
            }
            states.element().isFirst = false;
            states.push(states.element().onObject());
            try {
                super.visitObjectFieldWithVariable(ctx);
            } catch (MissingVariable e) {
                states.pop();
                stringBuilder.delete(len, stringBuilder.length());
                states.element().isFirst = previous;
            }
            states.pop();
            return this;
        }

        public JsonBuilder visitObjectValueWithVariable(GraphqlParser.ObjectValueWithVariableContext ctx) {
            states.push(states.element().onObject());
            super.visitObjectValueWithVariable(ctx);
            states.pop();
            return this;
        }

        public JsonBuilder visitBaseName(GraphqlParser.BaseNameContext ctx) {
            states.push(states.element().onBase());
            stringBuilder.append("\"");
            final JsonBuilder jsonBuilder = super.visitBaseName(ctx);
            stringBuilder.append("\"");
            states.pop();
            return jsonBuilder;
        }

        public JsonBuilder visitTerminal(TerminalNode node) {
            if (states.element().isObject || states.element().isArgument || states.element().isAttribut || states.element().isAValue) {
                stringBuilder.append(node.getText());
            }
            return super.visitTerminal(node);
        }
    }

    public static class ExtractValue extends GraphqlBaseVisitor<ExtractValue> {
        private final Map<String, String> variables;
        private String value = "";

        public ExtractValue(Map<String, String> variables) {
            this.variables = variables;
        }

        public ExtractValue visitVariable(GraphqlParser.VariableContext ctx) {
            ExtractName extractName = new ExtractName();
            extractName.visitVariable(ctx);
            if (!variables.containsKey(extractName.name)) {
                throw new RuntimeException("No value for variable " + extractName.name);
            }
            value = variables.get(extractName.name);
            return this;
        }

        public ExtractValue visitTerminal(TerminalNode node) {
            value = node.getText();
            return super.visitTerminal(node);
        }
    }

    public static class ExtractName extends GraphqlBaseVisitor<ExtractName> {
        private String name;

        public ExtractName visitName(GraphqlParser.NameContext ctx) {
            name = ctx.getText();
            return super.visitName(ctx);
        }
    }

    public AntlrGQLVisitor visitSelectionSet(GraphqlParser.SelectionSetContext ctx) {
        GQLGlobSelection selection = new GQLGlobSelection(schemaType.getField(operation == null ? "query" : operation)
                .asGlobField().getTargetType(), paramTypes, gqlGlobType1 -> gqlGlobType = gqlGlobType1);
        trees.push(selection);
        ExtractSelectionSet selectionSet = new ExtractSelectionSet();
        selectionSet.visitSelectionSet(ctx);
        return this;
    }

    @Override
    public AntlrGQLVisitor visitFragmentDefinition(GraphqlParser.FragmentDefinitionContext ctx) {
        GraphqlBaseVisitor<?> empty = new GraphqlBaseVisitor();
        empty.visitFragmentDefinition(ctx);
        return this;
    }

    public class ExtractSelectionSet extends GraphqlBaseVisitor<ExtractSelectionSet> {

        public ExtractSelectionSet visitSelection(GraphqlParser.SelectionContext ctx) {
            ExtractSelection selection = new ExtractSelection();
            selection.visitSelection(ctx);
            return this;
        }
    }

    public class ExtractSelection extends GraphqlBaseVisitor<ExtractSelection> {
        private String name;
        private String aliasName;

        @Override
        public ExtractSelection visitSelection(GraphqlParser.SelectionContext ctx) {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("visitSelection " + ctx.getText());
            }
            super.visitSelection(ctx);
            trees.pop().complete();
            return this;
        }

        public ExtractSelection visitArguments(GraphqlParser.ArgumentsContext ctx) {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("visitArguments " + ctx.getText());
            }
            MutableGlob mutableGlob = trees.element().getArguments();
            final StringBuilder stringBuilder = new StringBuilder();
            JsonBuilder jsonBuilder = new JsonBuilder(variables, stringBuilder);
//            Arguments arguments = new Arguments(mutableGlob, variables);
            jsonBuilder.visitArguments(ctx);
            final String s = stringBuilder.toString();
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Gson " + s);
            }
            GSonUtils.decode(new StringReader(s), mutableGlob.getType(), mutableGlob);
            return this;
        }

        public ExtractSelection visitName(GraphqlParser.NameContext ctx) {
            name = ctx.getText();
            GqlGlobBuilder gqlGlobBuilder = trees.element().addSub(name, aliasName == null ? name : aliasName);
            trees.push(gqlGlobBuilder);
            return super.visitName(ctx);
        }

        public ExtractSelection visitAlias(GraphqlParser.AliasContext ctx) {
            final ExtractName extractName = new ExtractName();
            extractName.visitAlias(ctx);
            aliasName = extractName.name;
            return this;
        }

        @Override
        public ExtractSelection visitSelectionSet(GraphqlParser.SelectionSetContext ctx) {
            ExtractSelectionSet selectionSet = new ExtractSelectionSet();
            final GqlGlobBuilder subBuilder = trees.element().getSubBuilder();
            trees.push(subBuilder);
            selectionSet.visitSelectionSet(ctx);
            trees.pop().complete();
            return this;
        }

        @Override
        public ExtractSelection visitFragmentName(GraphqlParser.FragmentNameContext ctx) {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("visit fragment name " + ctx.getText());
            }
            final ExtractSelection selection = super.visitFragmentName(ctx);
            final GraphqlParser.SelectionSetContext selectionSetContext = fragments.get(ctx.getText());
            super.visitSelectionSet(selectionSetContext);
            trees.push(new GqlGlobBuilderWithError() {
                public void complete() {
                }
            });
            return selection;
        }

        @Override
        public ExtractSelection visitFragmentSpread(GraphqlParser.FragmentSpreadContext ctx) {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("visit fragment Spread " + ctx.getText());
            }
            return super.visitFragmentSpread(ctx);
        }

        @Override
        public ExtractSelection visitInlineFragment(GraphqlParser.InlineFragmentContext ctx) {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("visit fragment Inline " + ctx.getText());
            }
            return super.visitInlineFragment(ctx);
        }

        @Override
        public ExtractSelection visitFragmentDefinition(GraphqlParser.FragmentDefinitionContext ctx) {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("visit Fragment Definition " + ctx.getText());
            }
            return super.visitFragmentDefinition(ctx);
        }
    }

}
