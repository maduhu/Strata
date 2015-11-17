/**
 * Copyright (C) 2015 - present by OpenGamma Inc. and the OpenGamma group of companies
 * 
 * Please see distribution for license.
 */
package com.opengamma.strata.loader.csv;

import static com.opengamma.strata.collect.Guavate.toImmutableMap;

import java.time.Period;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.math.DoubleMath;
import com.opengamma.strata.basics.date.Tenor;
import com.opengamma.strata.basics.market.FieldName;
import com.opengamma.strata.collect.Messages;
import com.opengamma.strata.collect.id.StandardId;
import com.opengamma.strata.collect.io.CsvFile;
import com.opengamma.strata.collect.io.ResourceLocator;
import com.opengamma.strata.market.curve.CurveGroupName;
import com.opengamma.strata.market.curve.CurveName;
import com.opengamma.strata.market.curve.definition.CurveGroupDefinition;
import com.opengamma.strata.market.curve.definition.CurveGroupDefinitionBuilder;
import com.opengamma.strata.market.curve.definition.CurveNode;
import com.opengamma.strata.market.curve.definition.FixedIborSwapCurveNode;
import com.opengamma.strata.market.curve.definition.FixedOvernightSwapCurveNode;
import com.opengamma.strata.market.curve.definition.FraCurveNode;
import com.opengamma.strata.market.curve.definition.FxSwapCurveNode;
import com.opengamma.strata.market.curve.definition.IborFixingDepositCurveNode;
import com.opengamma.strata.market.curve.definition.IborIborSwapCurveNode;
import com.opengamma.strata.market.curve.definition.NodalCurveDefinition;
import com.opengamma.strata.market.curve.definition.XCcyIborIborSwapCurveNode;
import com.opengamma.strata.market.id.DiscountCurveId;
import com.opengamma.strata.market.id.RateCurveId;
import com.opengamma.strata.market.id.RateIndexCurveId;
import com.opengamma.strata.market.key.QuoteKey;
import com.opengamma.strata.product.fra.type.FraConvention;
import com.opengamma.strata.product.fra.type.FraTemplate;
import com.opengamma.strata.product.fx.type.FxSwapConvention;
import com.opengamma.strata.product.fx.type.FxSwapTemplate;
import com.opengamma.strata.product.rate.deposit.type.IborFixingDepositConvention;
import com.opengamma.strata.product.rate.deposit.type.IborFixingDepositTemplate;
import com.opengamma.strata.product.swap.type.FixedIborSwapConvention;
import com.opengamma.strata.product.swap.type.FixedIborSwapTemplate;
import com.opengamma.strata.product.swap.type.FixedOvernightSwapConvention;
import com.opengamma.strata.product.swap.type.FixedOvernightSwapTemplate;
import com.opengamma.strata.product.swap.type.IborIborSwapConvention;
import com.opengamma.strata.product.swap.type.IborIborSwapTemplate;
import com.opengamma.strata.product.swap.type.XCcyIborIborSwapConvention;
import com.opengamma.strata.product.swap.type.XCcyIborIborSwapTemplate;

/**
 * Loads a set of definitions to calibrate rates curves by reading from CSV resources.
 * <p>
 * There are three type of CSV files.
 * <p>
 * The first file is the curve group metadata file.
 * This file has the following header row:<br />
 * {@code Group Name, Curve Type, Reference, Curve Name}.
 * <ul>
 * <li>The 'Group Name' column is the name of the group of curves.
 * <li>The 'Curve Type' column is the type of the curve, "forward" or "discount".
 * <li>The 'Reference' column is the reference the curve is used for, such as "USD" or "USD-LIBOR-3M".
 * <li>The 'Curve Name' column is the name of the curve.
 * </ul>
 * <p>
 * The second file is the curve settings metadata file.
 * This file has the following header row:<br />
 * {@code Curve Name, Value Type, Day Count, Interpolator, Left Extrapolator, Right Extrapolator}.
 * <ul>
 * <li>The 'Curve Name' column is the name of the curve.
 * <li>The 'Value Type' column is the type of data in the curve, "zero" for zero rates, or "df" for discount factors.
 * <li>The 'Day Count' column is the name of the day count, such as "Act/365F".
 * <li>The 'Interpolator' and extrapolator columns define the interpolator to use.
 * </ul>
 * <p>
 * The third file is the curve calibration nodes file.
 * This file has the following header row:<br />
 * {@code Curve Name,Label,Quote Symbology,Quote Ticker,Quote Field Name,FX Symbology,FX Ticker,FX Field Name,Type,Convention,Time,Spread}.
 * <ul>
 * <li>The 'Curve Name' column is the name of the curve.
 * <li>The 'Label' column is the label used to refer to the node.
 * <li>The 'Quote Symbology' column is the symbology scheme applicable to the ticker used for the market price.
 * <li>The 'Quote Ticker' column is the identifier within the symbology used for the market price.
 * <li>The 'Quote Field Name' column is the field name used for the market price, defaulted to "MarketValue", allowing
 *  fields such as 'Bid' or 'Ask' to be specified.
 * <li>The 'FX Symbology' column is the symbology scheme applicable to the ticker used for the FX rate.
 * <li>The 'FX Ticker' column is the identifier within the symbology used for the FX rate.
 * <li>The 'FX Field Name' column is the field name used for the FX rate, defaulted to "MarketValue", allowing
 *  fields such as 'Bid' or 'Ask' to be specified.
 * <li>The 'Type' column is the type of the instrument, such as "FRA" or "OIS".
 * <li>The 'Convention' column is the name of the convention to use.
 * <li>The 'Time' column is the description of the time, such as "1Y" for a 1 year swap, or "3Mx6M" for a FRA.
 * <li>The 'Spread' column is the spread to add to the instrument.
 * </ul>
 * <p>
 * Note that the three FX fields are only needed if specifying a cross-currency product.
 * <p>
 * Each curve must be contained entirely within a single file, but each file may contain more than
 * one curve. The curve points do not need to be ordered.
 */
public final class RatesCalibrationCsvLoader {

  // CSV column headers
  private static final String CURVE_NAME = "Curve Name";
  private static final String CURVE_LABEL = "Label";
  private static final String CURVE_SYMBOLOGY_QUOTE = "Quote Symbology";
  private static final String CURVE_TICKER_QUOTE = "Quote Ticker";
  private static final String CURVE_FIELD_QUOTE = "Quote Field Name";
  private static final String CURVE_SYMBOLOGY_FX = "FX Symbology";
  private static final String CURVE_TICKER_FX = "FX Ticker";
  private static final String CURVE_FIELD_FX = "FX Field Name";
  private static final String CURVE_TYPE = "Type";
  private static final String CURVE_CONVENTION = "Convention";
  private static final String CURVE_TIME = "Time";
  private static final String CURVE_SPREAD = "Spread";

  // Regex to parse FRA time string
  private static final Pattern FRA_TIME_REGEX = Pattern.compile("P?([0-9]+)M? ?X ?P?([0-9]+)M?");
  // Regex to parse simple time string
  private static final Pattern SIMPLE_TIME_REGEX = Pattern.compile("P?(([0-9]+M)?([0-9]+Y)?)");

  //-------------------------------------------------------------------------
  /**
   * Loads one or more CSV format curve calibration files.
   * <p>
   * If the files contain a duplicate entry an exception will be thrown.
   * 
   * @param groupsResource  the curve groups CSV resource
   * @param settingsResource  the curve settings CSV resource
   * @param curveResources  the CSV resources for curves
   * @return the loaded curves, mapped by an identifying key
   * @throws IllegalArgumentException if the files contain a duplicate entry
   */
  public static ImmutableMap<CurveGroupName, CurveGroupDefinition> load(
      ResourceLocator groupsResource,
      ResourceLocator settingsResource,
      ResourceLocator... curveResources) {

    return load(groupsResource, settingsResource, ImmutableList.copyOf(curveResources));
  }

  /**
   * Loads one or more CSV format curve calibration files.
   * <p>
   * If the files contain a duplicate entry an exception will be thrown.
   * 
   * @param groupsResource  the curve groups CSV resource
   * @param settingsResource  the curve settings CSV resource
   * @param curveResources  the CSV resources for curves
   * @return the loaded curves, mapped by an identifying key
   * @throws IllegalArgumentException if the files contain a duplicate entry
   */
  public static ImmutableMap<CurveGroupName, CurveGroupDefinition> load(
      ResourceLocator groupsResource,
      ResourceLocator settingsResource,
      Collection<ResourceLocator> curveResources) {

    // load curve groups and settings
    Map<CurveName, Set<RateCurveId>> curveGroups = RatesCurvesCsvLoader.loadCurveGroups(groupsResource);
    Map<CurveName, LoadedCurveSettings> settingsMap = RatesCurvesCsvLoader.loadCurveSettings(settingsResource);

    // load curves
    // builder ensures keys can only be seen once
    ImmutableMap.Builder<CurveName, NodalCurveDefinition> curvesBuilder = new ImmutableMap.Builder<>();
    for (ResourceLocator curvesResource : curveResources) {
      curvesBuilder.putAll(loadSingle(curvesResource, settingsMap));
    }
    ImmutableMap<CurveName, NodalCurveDefinition> curves = curvesBuilder.build();
    return mapGroups(curveGroups, curves);
  }

  // uses the curve groups to form the resolved map of curve id to curve
  private static ImmutableMap<CurveGroupName, CurveGroupDefinition> mapGroups(
      Map<CurveName, Set<RateCurveId>> curveGroupMappings,
      Map<CurveName, NodalCurveDefinition> curves) {

    Map<CurveGroupName, CurveGroupDefinitionBuilder> resultBuilder = new HashMap<>();
    for (Map.Entry<CurveName, NodalCurveDefinition> entry : curves.entrySet()) {
      CurveName curveName = entry.getKey();
      Set<RateCurveId> curveUses = curveGroupMappings.get(curveName);
      // ignore if curve not mapped in any group
      if (curveUses != null) {
        for (RateCurveId curveUse : curveUses) {
          if (curveUse instanceof DiscountCurveId) {
            DiscountCurveId discountCurveId = (DiscountCurveId) curveUse;
            CurveGroupName groupName = discountCurveId.getCurveGroupName();
            CurveGroupDefinitionBuilder builder =
                resultBuilder.computeIfAbsent(groupName, k -> CurveGroupDefinition.builder().name(groupName));
            builder.addDiscountCurve(entry.getValue(), discountCurveId.getCurrency());
          } else if (curveUse instanceof RateIndexCurveId) {
            RateIndexCurveId rateCurveId = (RateIndexCurveId) curveUse;
            CurveGroupName groupName = rateCurveId.getCurveGroupName();
            CurveGroupDefinitionBuilder builder =
                resultBuilder.computeIfAbsent(groupName, k -> CurveGroupDefinition.builder().name(groupName));
            builder.addForwardCurve(entry.getValue(), rateCurveId.getIndex());
          } else {
            throw new IllegalArgumentException(
                Messages.format("Unknown RateCurveId subclass: {}", curveUse.getClass().getName()));
          }
        }
      }
    }
    return resultBuilder.entrySet().stream()
        .collect(toImmutableMap(e -> e.getKey(), e -> e.getValue().build()));
  }

  //-------------------------------------------------------------------------
  // loads a single curves CSV file
  // requestedDate can be null, meaning load all dates
  private static Map<CurveName, NodalCurveDefinition> loadSingle(
      ResourceLocator resource,
      Map<CurveName, LoadedCurveSettings> settingsMap) {

    CsvFile csv = CsvFile.of(resource.getCharSource(), true);
    Map<CurveName, List<CurveNode>> allNodes = new HashMap<>();
    for (int i = 0; i < csv.rowCount(); i++) {
      String curveNameStr = csv.field(i, CURVE_NAME);
      String label = csv.field(i, CURVE_LABEL);
      String symbologyQuoteStr = csv.field(i, CURVE_SYMBOLOGY_QUOTE);
      String tickerQuoteStr = csv.field(i, CURVE_TICKER_QUOTE);
      String fieldQuoteStr = csv.field(i, CURVE_FIELD_QUOTE);
      String symbologyFxStr = csv.field(i, CURVE_SYMBOLOGY_FX);
      String tickerFxStr = csv.field(i, CURVE_TICKER_FX);
      String fieldFxStr = csv.field(i, CURVE_FIELD_FX);
      String typeStr = csv.field(i, CURVE_TYPE);
      String conventionStr = csv.field(i, CURVE_CONVENTION);
      String timeStr = csv.field(i, CURVE_TIME);
      String spreadStr = csv.field(i, CURVE_SPREAD);

      CurveName curveName = CurveName.of(curveNameStr);
      StandardId quoteId = StandardId.of(symbologyQuoteStr, tickerQuoteStr);
      FieldName quoteField = fieldQuoteStr.isEmpty() ? FieldName.MARKET_VALUE : FieldName.of(fieldQuoteStr);
      QuoteKey quoteKey = QuoteKey.of(quoteId, quoteField);
      QuoteKey fxKey = null;
      if (!symbologyFxStr.isEmpty() && !tickerFxStr.isEmpty()) {
        StandardId fxId = StandardId.of(symbologyFxStr, tickerFxStr);
        FieldName fxField = fieldFxStr.isEmpty() ? FieldName.MARKET_VALUE : FieldName.of(fieldFxStr);
        fxKey = QuoteKey.of(fxId, fxField);
      }
      double spread = spreadStr.isEmpty() ? 0d : Double.parseDouble(spreadStr);

      List<CurveNode> curveNodes = allNodes.computeIfAbsent(curveName, k -> new ArrayList<CurveNode>());
      curveNodes.add(createCurveNode(typeStr, conventionStr, timeStr, label, quoteKey, fxKey, spread));
    }
    return buildCurveDefinition(settingsMap, allNodes);
  }

  // build the curves
  private static Map<CurveName, NodalCurveDefinition> buildCurveDefinition(
      Map<CurveName, LoadedCurveSettings> settingsMap,
      Map<CurveName, List<CurveNode>> allNodes) {

    ImmutableMap.Builder<CurveName, NodalCurveDefinition> results = ImmutableMap.builder();
    for (Map.Entry<CurveName, List<CurveNode>> entry : allNodes.entrySet()) {
      CurveName name = entry.getKey();
      LoadedCurveSettings settings = settingsMap.get(name);
      if (settings == null) {
        throw new IllegalArgumentException(Messages.format("Missing settings for curve: {}", name));
      }
      results.put(name, settings.createCurveDefinition(entry.getValue()));
    }
    return results.build();
  }

  //-------------------------------------------------------------------------
  // create the curve node
  private static CurveNode createCurveNode(
      String typeStr,
      String conventionStr,
      String timeStr,
      String label,
      QuoteKey quoteKey,
      QuoteKey fxKey,
      double spread) {

    if ("FIX".equalsIgnoreCase(typeStr) || "IborFixingDeposit".equalsIgnoreCase(typeStr)) {
      return curveIborFixingDepositCurveNode(conventionStr, timeStr, label, quoteKey, spread);
    }
    if ("FRA".equalsIgnoreCase(typeStr)) {
      return curveFraCurveNode(conventionStr, timeStr, label, quoteKey, spread);
    }
    if ("OIS".equalsIgnoreCase(typeStr) || "FixedOvernightSwap".equalsIgnoreCase(typeStr)) {
      return curveFixedOvernightCurveNode(conventionStr, timeStr, label, quoteKey, spread);
    }
    if ("IRS".equalsIgnoreCase(typeStr) || "FixedIborSwap".equalsIgnoreCase(typeStr)) {
      return curveFixedIborCurveNode(conventionStr, timeStr, label, quoteKey, spread);
    }
    if ("BAS".equalsIgnoreCase(typeStr) || "IborIborSwap".equalsIgnoreCase(typeStr)) {
      return curveIborIborCurveNode(conventionStr, timeStr, label, quoteKey, spread);
    }
    if ("XCS".equalsIgnoreCase(typeStr) || "XCcyIborIborSwap".equalsIgnoreCase(typeStr)) {
      return curveXCcyIborIborCurveNode(conventionStr, timeStr, label, quoteKey, fxKey, spread);
    }
    if ("FXS".equalsIgnoreCase(typeStr) || "FxSwap".equalsIgnoreCase(typeStr)) {
      return curveFxSwapCurveNode(conventionStr, timeStr, label, quoteKey, fxKey, spread);
    }
    throw new IllegalArgumentException(Messages.format("Invalid curve node type: {}", typeStr));
  }

  private static CurveNode curveIborFixingDepositCurveNode(
      String conventionStr,
      String timeStr,
      String label,
      QuoteKey quoteKey,
      double spread) {

    IborFixingDepositConvention convention = IborFixingDepositConvention.of(conventionStr);
    IborFixingDepositTemplate template = IborFixingDepositTemplate.of(
        convention.getIndex().getTenor().getPeriod(), convention);
    return IborFixingDepositCurveNode.of(template, quoteKey, spread);
  }

  private static CurveNode curveFraCurveNode(
      String conventionStr,
      String timeStr,
      String label,
      QuoteKey quoteKey,
      double spread) {

    Matcher matcher = FRA_TIME_REGEX.matcher(timeStr.toUpperCase(Locale.ENGLISH));
    if (!matcher.matches()) {
      throw new IllegalArgumentException(Messages.format("Invalid time format for FRA: {}", timeStr));
    }
    Period periodToStart = Period.parse("P" + matcher.group(1) + "M");
    Period periodToEnd = Period.parse("P" + matcher.group(2) + "M");

    FraConvention convention = FraConvention.of(conventionStr);
    FraTemplate template = FraTemplate.of(periodToStart, periodToEnd, convention);
    return FraCurveNode.of(template, quoteKey, spread);
  }

  //-------------------------------------------------------------------------
  private static CurveNode curveFixedOvernightCurveNode(
      String conventionStr,
      String timeStr,
      String label,
      QuoteKey quoteKey,
      double spread) {

    Matcher matcher = SIMPLE_TIME_REGEX.matcher(timeStr.toUpperCase(Locale.ENGLISH));
    if (!matcher.matches()) {
      throw new IllegalArgumentException(Messages.format("Invalid time format for Fixed-Overnight swap: {}", timeStr));
    }
    Period periodToEnd = Period.parse("P" + matcher.group(1));
    FixedOvernightSwapConvention convention = FixedOvernightSwapConvention.of(conventionStr);
    FixedOvernightSwapTemplate template = FixedOvernightSwapTemplate.of(Tenor.of(periodToEnd), convention);
    return FixedOvernightSwapCurveNode.of(template, quoteKey, spread);
  }

  private static CurveNode curveFixedIborCurveNode(
      String conventionStr,
      String timeStr,
      String label,
      QuoteKey quoteKey,
      double spread) {

    Matcher matcher = SIMPLE_TIME_REGEX.matcher(timeStr.toUpperCase(Locale.ENGLISH));
    if (!matcher.matches()) {
      throw new IllegalArgumentException(Messages.format("Invalid time format for Fixed-Ibor swap: {}", timeStr));
    }
    Period periodToEnd = Period.parse("P" + matcher.group(1));
    FixedIborSwapConvention convention = FixedIborSwapConvention.of(conventionStr);
    FixedIborSwapTemplate template = FixedIborSwapTemplate.of(Tenor.of(periodToEnd), convention);
    return FixedIborSwapCurveNode.of(template, quoteKey, spread);
  }

  private static CurveNode curveIborIborCurveNode(
      String conventionStr,
      String timeStr,
      String label,
      QuoteKey quoteKey,
      double spread) {

    Matcher matcher = SIMPLE_TIME_REGEX.matcher(timeStr.toUpperCase(Locale.ENGLISH));
    if (!matcher.matches()) {
      throw new IllegalArgumentException(Messages.format("Invalid time format for Ibor-Ibor swap: {}", timeStr));
    }
    Period periodToEnd = Period.parse("P" + matcher.group(1));
    IborIborSwapConvention convention = IborIborSwapConvention.of(conventionStr);
    IborIborSwapTemplate template = IborIborSwapTemplate.of(Tenor.of(periodToEnd), convention);
    return IborIborSwapCurveNode.of(template, quoteKey, spread);
  }

  private static CurveNode curveXCcyIborIborCurveNode(
      String conventionStr,
      String timeStr,
      String label,
      QuoteKey quoteKey,
      QuoteKey fxKey,
      double spread) {

    if (fxKey == null) {
      throw new IllegalArgumentException("FX quote information required for cross currency swap");
    }
    Matcher matcher = SIMPLE_TIME_REGEX.matcher(timeStr.toUpperCase(Locale.ENGLISH));
    if (!matcher.matches()) {
      throw new IllegalArgumentException(Messages.format("Invalid time format for XCS: {}", timeStr));
    }
    Period periodToEnd = Period.parse("P" + matcher.group(1));
    XCcyIborIborSwapConvention convention = XCcyIborIborSwapConvention.of(conventionStr);
    XCcyIborIborSwapTemplate template = XCcyIborIborSwapTemplate.of(Tenor.of(periodToEnd), convention);
    return XCcyIborIborSwapCurveNode.of(template, quoteKey, fxKey, spread);
  }

  //-------------------------------------------------------------------------
  private static CurveNode curveFxSwapCurveNode(
      String conventionStr,
      String timeStr,
      String label,
      QuoteKey quoteKey,
      QuoteKey fxKey,
      double spread) {

    if (fxKey == null) {
      throw new IllegalArgumentException("FX quote information required for FX swap");
    }
    if (!DoubleMath.fuzzyEquals(spread, 0d, 1e-10d)) {
      throw new IllegalArgumentException("Additional spread must be zero for FX swaps");
    }
    Matcher matcher = SIMPLE_TIME_REGEX.matcher(timeStr.toUpperCase(Locale.ENGLISH));
    if (!matcher.matches()) {
      throw new IllegalArgumentException(Messages.format("Invalid time format for FX swap: {}", timeStr));
    }
    Period periodToEnd = Period.parse("P" + matcher.group(1));
    FxSwapConvention convention = FxSwapConvention.of(conventionStr);
    FxSwapTemplate template = FxSwapTemplate.of(periodToEnd, convention);
    return FxSwapCurveNode.of(template, quoteKey, fxKey);
  }

  //-------------------------------------------------------------------------
  /**
   * Restricted constructor.
   */
  private RatesCalibrationCsvLoader() {
  }

}