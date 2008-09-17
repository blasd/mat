/*******************************************************************************
 * Copyright (c) 2008 SAP AG.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    SAP AG - initial API and implementation
 *******************************************************************************/
package org.eclipse.mat.report.internal;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.collect.ArrayInt;
import org.eclipse.mat.query.Column;
import org.eclipse.mat.query.ContextProvider;
import org.eclipse.mat.query.IQueryContext;
import org.eclipse.mat.query.IResult;
import org.eclipse.mat.query.IResultPie;
import org.eclipse.mat.query.IResultTable;
import org.eclipse.mat.query.IResultTree;
import org.eclipse.mat.query.ResultMetaData;
import org.eclipse.mat.query.ContextDerivedData.DerivedColumn;
import org.eclipse.mat.query.ContextDerivedData.DerivedOperation;
import org.eclipse.mat.query.refined.RefinedResultBuilder;
import org.eclipse.mat.query.refined.RefinedStructuredResult;
import org.eclipse.mat.query.refined.RefinedTable;
import org.eclipse.mat.query.refined.RefinedTree;
import org.eclipse.mat.query.registry.CommandLine;
import org.eclipse.mat.query.results.CompositeResult;
import org.eclipse.mat.report.ITestResult;
import org.eclipse.mat.report.Params;
import org.eclipse.mat.report.QuerySpec;
import org.eclipse.mat.report.SectionSpec;
import org.eclipse.mat.report.Spec;
import org.eclipse.mat.report.ITestResult.Status;
import org.eclipse.mat.util.IProgressListener;

public class QueryPart extends AbstractPart
{
    public QueryPart(AbstractPart parent, QuerySpec spec)
    {
        super(parent, spec);
    }

    @Override
    public QuerySpec spec()
    {
        return (QuerySpec) super.spec();
    }

    public String getCommand()
    {
        // expand variables in command
        return params().expand(spec().getCommand());
    }

    @Override
    public AbstractPart execute(IQueryContext context, ResultRenderer renderer, IProgressListener listener)
                    throws SnapshotException, IOException
    {
        String sectionName = parent != null ? parent.spec().getName() : "none";
        listener.subTask(MessageFormat.format("Test ''{0}'' of section ''{1}''", spec().getName(), sectionName));

        IResult result = spec().getResult();

        if (result == null)
        {
            if (getCommand() == null)
            {
                ReportPlugin.log(IStatus.ERROR, MessageFormat.format(
                                "No command specified for test ''{0}'' of section ''{1}''", //
                                spec().getName(), sectionName));
            }
            else
            {
                try
                {
                    result = CommandLine.execute(context, getCommand(), new SilentListener(listener));
                }
                catch (Exception e)
                {
                    ReportPlugin.log(e, MessageFormat.format("Ignoring result of ''{0}'' due to {1}", spec().getName(),
                                    e.getMessage()));
                    return this;
                }
            }
        }

        // create a spec (to be merged) for the composite result
        if (result instanceof CompositeResult)
            result = buildFor((CompositeResult) result);

        // merge specs into the current part hierarchy (and remove yourself)
        if (result instanceof Spec)
        {
            Spec replacement = (Spec) result;

            // overwrite all parameters explicitly given
            replacement.putAll(spec().getParams());
            replacement.setName(spec().getName());

            AbstractPart part = AbstractPart.build(getParent(), replacement);
            part.objects = objects;
            part.filename = filename;
            return part.execute(context, renderer, listener);
        }

        // set status if test discloses one
        if (result instanceof ITestResult)
            status = ((ITestResult) result).getStatus();

        // try not to wrap RefinedResult if it is not needed
        boolean hasParameterThatNeedRefining = hasParameterThatNeedRefining();

        // read and process parameters for tree and table and pass 'em on to the
        // renderer
        if (result instanceof RefinedTable && !hasParameterThatNeedRefining)
        {
            readParamsAndProcess(renderer, (RefinedStructuredResult) result);
        }
        else if (result instanceof IResultTable)
        {
            RefinedResultBuilder builder = new RefinedResultBuilder(context, (IResultTable) result);
            readParamsAndProcess(renderer, context, result, builder);
        }
        else if (result instanceof RefinedTree && !hasParameterThatNeedRefining)
        {
            readParamsAndProcess(renderer, (RefinedStructuredResult) result);
        }
        else if (result instanceof IResultTree)
        {
            RefinedResultBuilder builder = new RefinedResultBuilder(context, (IResultTree) result);
            readParamsAndProcess(renderer, context, result, builder);
        }
        else if (result instanceof IResultPie && Platform.getBundle("org.eclipse.mat.chart.ui") == null)
        {
            // do not render the result if pie charts are not available
        }
        else
        {
            RenderingInfo rInfo = new RenderingInfo(this, renderer);
            renderer.process(this, result, rInfo);
        }

        for (int ii = 0; ii < this.children.size(); ii++)
        {
            AbstractPart part = this.children.get(ii).execute(context, renderer, listener);
            this.status = Status.max(this.status, part.status);
            this.children.set(ii, part);
        }

        return this;
    }

    private boolean hasParameterThatNeedRefining()
    {
        String[] providers = params().getStringArray(Params.Rendering.DERIVED_DATA_COLUMN);
        if (providers != null && providers.length > 0)
            return true;

        String sortColumn = params().get(Params.Rendering.SORT_COLUMN);
        if (sortColumn != null)
            return true;

        String[] filters = params().getStringArray(Params.Rendering.FILTER);
        if (filters != null && filters.length > 0)
            return true;

        String[] hidden = params().getStringArray(Params.Rendering.HIDE_COLUMN);
        if (hidden != null && hidden.length > 0)
            return true;

        return false;
    }

    // //////////////////////////////////////////////////////////////
    // build specs for an existing composite result
    // //////////////////////////////////////////////////////////////

    private Spec buildFor(CompositeResult result)
    {
        String name = result.getName() != null ? result.getName() : spec().getName();
        SectionSpec spec = new SectionSpec(name);
        spec.setStatus(result.getStatus());

        String pattern = params().shallow().get(Params.Rendering.PATTERN);
        boolean isOverviewDetailsPattern = Params.Rendering.PATTERN_OVERVIEW_DETAILS.equals(pattern);

        int index = 1;
        for (CompositeResult.Entry entry : result.getResultEntries())
        {
            String label = entry.getName();
            if (label == null && entry.getResult() instanceof Spec)
                label = ((Spec) entry.getResult()).getName();
            if (label == null)
                label = spec().getName() + " " + index;

            QuerySpec q = new QuerySpec(label);
            q.setResult(entry.getResult());

            if (index == 1 && isOverviewDetailsPattern)
            {
                q.set(Params.Rendering.PATTERN, Params.Rendering.PATTERN_OVERVIEW_DETAILS);
                q.set(Params.Html.IS_IMPORTANT, params().get(Params.Html.IS_IMPORTANT));
            }

            spec.add(q);

            index++;
        }

        return spec;
    }

    // //////////////////////////////////////////////////////////////
    // read parameters and inject into refined result builder
    // //////////////////////////////////////////////////////////////

    private void readParamsAndProcess(ResultRenderer renderer, //
                    IQueryContext context, //
                    IResult result, //
                    RefinedResultBuilder builder) throws IOException
    {
        builder.setInlineRetainedSizeCalculation(true);

        addDerivedDataColumns(context, builder, result);
        addSortOrder(builder);
        addFilter(builder);

        RenderingInfo rInfo = new RenderingInfo(this, renderer, builder.getColumns().size());

        addHiding(builder, rInfo);
        addHardLimit(rInfo);
        addShowTotals(rInfo);

        renderer.process(this, builder.build(), rInfo);
    }

    private void readParamsAndProcess(ResultRenderer renderer, RefinedStructuredResult result) throws IOException
    {
        RenderingInfo rInfo = new RenderingInfo(this, renderer, result.getColumns().length);
        addHardLimit(rInfo);
        addShowTotals(rInfo);

        renderer.process(this, result, rInfo);
    }

    private void addShowTotals(RenderingInfo rInfo)
    {
        rInfo.setShowTotals(params().getBoolean(Params.Html.SHOW_TOTALS, true));
    }

    private void addHiding(RefinedResultBuilder builder, RenderingInfo rInfo)
    {
        String[] hidden = params().getStringArray(Params.Rendering.HIDE_COLUMN);
        if (hidden == null || hidden.length == 0)
            return;

        for (String column : hidden)
        {
            int columnIndex = builder.getColumnIndexByName(column);
            if (columnIndex < 0)
            {
                Logger.getLogger(getClass().getName()).log(Level.SEVERE,
                                MessageFormat.format("Column not found: {0}", column));
            }
            else
            {
                rInfo.setColumnVisible(columnIndex, false);
            }
        }
    }

    private void addHardLimit(RenderingInfo info)
    {
        info.setLimit(params().getInt(Params.Rendering.LIMIT, 25));
    }

    /** format: filter1=criteria,filter2=criteria */
    private void addFilter(RefinedResultBuilder builder)
    {
        String[] filters = params().getStringArray(Params.Rendering.FILTER);
        if (filters == null || filters.length == 0)
            return;

        for (String filter : filters)
        {
            int p = filter.indexOf('=');
            if (p < 0)
            {
                Logger.getLogger(getClass().getName()).log(Level.SEVERE,
                                MessageFormat.format("Missing ''='' sign in filter ''{0}''", filter));
            }
            else
            {
                int columnIndex = builder.getColumnIndexByName(filter.substring(0, p));
                if (columnIndex < 0)
                {
                    Logger.getLogger(getClass().getName()).log(Level.SEVERE,
                                    MessageFormat.format("Column not found: {0}", filter.substring(0, p)));
                }
                else
                {
                    try
                    {
                        builder.setFilter(columnIndex, filter.substring(p + 1));
                    }
                    catch (IllegalArgumentException e)
                    {
                        Logger.getLogger(getClass().getName()).log(Level.SEVERE,
                                        MessageFormat.format("Error in filter: {0}", filter.substring(p + 1)), e);
                    }
                }
            }
        }
    }

    /** format: filter1=criteria,filter2=criteria */
    /**
     * format: contextprovider1=operation,contextprovider2=operation,_default_=
     * operation
     */
    private void addDerivedDataColumns(IQueryContext context, RefinedResultBuilder builder, IResult result)
    {
        String[] providers = params().getStringArray(Params.Rendering.DERIVED_DATA_COLUMN);
        if (providers == null || providers.length == 0)
            return;

        ResultMetaData metaData = result.getResultMetaData();

        for (String provider : providers)
        {
            int p = provider.indexOf('=');
            if (p < 0)
            {
                ReportPlugin.log(IStatus.WARNING, MessageFormat.format(
                                "Invalid configuration parameter {0}: expected <provider>=<operation>: {1}",
                                Params.Rendering.DERIVED_DATA_COLUMN, provider));
                continue;
            }

            String code = provider.substring(p + 1);

            DerivedOperation operation = null;

            for (DerivedColumn derivedColumn : context.getContextDerivedData().getDerivedColumns())
            {
                for (DerivedOperation derivedOperation : derivedColumn.getOperations())
                {
                    if (code.equals(derivedOperation.getCode()))
                    {
                        operation = derivedOperation;
                        break;
                    }
                }
            }

            if (operation == null)
            {
                ReportPlugin.log(IStatus.WARNING, MessageFormat.format(
                                "Invalid configuration paramter {0}: unknown operation: {1}",
                                Params.Rendering.DERIVED_DATA_COLUMN, code));
                continue;
            }

            provider = provider.substring(0, p);

            boolean added = false;

            if ("_default_".equals(provider))
            {
                builder.addDefaultContextDerivedColumn(operation);
                added = true;
            }
            else
            {
                if (metaData != null)
                {
                    for (ContextProvider cp : metaData.getContextProviders())
                    {
                        if (provider.equals(cp.getLabel()))
                        {
                            builder.addContextDerivedColumn(cp, operation);
                            added = true;
                            break;
                        }
                    }
                }
            }

            if (!added)
            {
                String msg = "Error added retained size column for ''{0}'' - no context provider found.";
                Logger.getLogger(QueryPart.class.getName()).log(Level.WARNING, MessageFormat.format(msg, provider));
            }

        }
    }

    private void addSortOrder(RefinedResultBuilder builder)
    {
        String[] columns = params().getStringArray(Params.Rendering.SORT_COLUMN);
        if (columns == null || columns.length == 0)
            return;

        ArrayInt indices = new ArrayInt(columns.length);
        List<Column.SortDirection> directions = new ArrayList<Column.SortDirection>(columns.length);

        for (String column : columns)
        {
            int p = column.indexOf('=');

            String name = p < 0 ? column : column.substring(0, p);
            Column.SortDirection direction = p < 0 ? null : Column.SortDirection.valueOf(column.substring(p + 1));

            int columnIndex = builder.getColumnIndexByName(name);
            if (columnIndex < 0)
            {
                Logger.getLogger(getClass().getName()).log(Level.WARNING,
                                MessageFormat.format("Sort column not found: {0}", name));
                continue;
            }

            indices.add(columnIndex);
            directions.add(direction);
        }

        builder.setSortOrder(indices.toArray(), directions.toArray(new Column.SortDirection[0]));
    }

    private static final class SilentListener implements IProgressListener
    {
        IProgressListener delegate;

        public SilentListener(IProgressListener delegate)
        {
            this.delegate = delegate;
        }

        public void beginTask(String name, int totalWork)
        {}

        public void done()
        {}

        public boolean isCanceled()
        {
            return delegate.isCanceled();
        }

        public void sendUserMessage(Severity severity, String message, Throwable exception)
        {
            delegate.sendUserMessage(severity, message, exception);
        }

        public void setCanceled(boolean value)
        {}

        public void subTask(String name)
        {}

        public void worked(int work)
        {}

    }
}