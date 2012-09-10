/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */
package com.akiban.server.types3.mcompat.mfuncs;

import com.akiban.server.explain.*;
import com.akiban.server.types3.LazyList;
import com.akiban.server.types3.TClass;
import com.akiban.server.types3.TCustomOverloadResult;
import com.akiban.server.types3.TExecutionContext;
import com.akiban.server.types3.TInstance;
import com.akiban.server.types3.TOverload;
import com.akiban.server.types3.TOverloadResult;
import com.akiban.server.types3.TPreptimeContext;
import com.akiban.server.types3.TPreptimeValue;
import com.akiban.server.types3.common.BigDecimalWrapper;
import com.akiban.server.types3.common.funcs.TArithmetic;
import com.akiban.server.types3.mcompat.mtypes.MApproximateNumber;
import com.akiban.server.types3.mcompat.mtypes.MBigDecimal.Attrs;
import com.akiban.server.types3.mcompat.mtypes.MBigDecimal;
import com.akiban.server.types3.mcompat.mtypes.MBigDecimalWrapper;
import com.akiban.server.types3.mcompat.mtypes.MNumeric;
import com.akiban.server.types3.pvalue.PValueSource;
import com.akiban.server.types3.pvalue.PValueTarget;
import com.akiban.server.types3.texpressions.TPreparedExpression;

import java.util.List;

public abstract class MArithmetic extends TArithmetic {

    private static final int DEC_INDEX = 0;
    
    private final String infix;
    private final boolean associative;
    
    private MArithmetic(String overloadName, String infix, boolean associative, TClass inputType, TInstance resultType) {
        super(overloadName, inputType, resultType);
        this.infix = infix;
        this.associative = associative;
    }

    @Override
    protected String toStringName() {
        return "Arith";
    }

    @Override
    protected String toStringArgsPrefix() {
        return infix + " -> ";
    }

    @Override
    public CompoundExplainer getExplainer(ExplainContext context, List<? extends TPreparedExpression> inputs, TInstance resultType)
    {
        CompoundExplainer ex = super.getExplainer(context, inputs, resultType);
        if (infix != null)
            ex.addAttribute(Label.INFIX_REPRESENTATION, PrimitiveExplainer.getInstance(infix));
        if (associative)
            ex.addAttribute(Label.ASSOCIATIVE, PrimitiveExplainer.getInstance(associative));
        return ex;
    }

    private static BigDecimalWrapper getWrapper(TExecutionContext context)
    {
        BigDecimalWrapper wrapper = (BigDecimalWrapper)context.exectimeObjectAt(DEC_INDEX);
        // Why would we need a Supplier?
        if (wrapper == null)
            context.putExectimeObject(DEC_INDEX, wrapper = new MBigDecimalWrapper());
        wrapper.reset();
        return wrapper;
    }
    
    // Add functions
    public static final TOverload ADD_TINYINT = new MArithmetic("plus", "+", true, MNumeric.TINYINT, MNumeric.MEDIUMINT.instance(5)) {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output) {
            int a0 = inputs.get(0).getInt8();
            int a1 = inputs.get(0).getInt8();
            output.putInt32(a0 + a1);
        }
    };

    public static final TOverload ADD_SMALLINT = new MArithmetic("plus", "+", true, MNumeric.SMALLINT, MNumeric.MEDIUMINT.instance(7)) {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output) {
            int a0 = inputs.get(0).getInt16();
            int a1 = inputs.get(0).getInt16();
            output.putInt32(a0 + a1);
        }
    };

    public static final TOverload ADD_MEDIUMINT = new MArithmetic("plus", "+", true, MNumeric.MEDIUMINT, MNumeric.INT.instance(9)) {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output) {
            int a0 = inputs.get(0).getInt32();
            int a1 = inputs.get(1).getInt32();       
            output.putInt32(a0 + a1);
        }
    };

    public static final TOverload ADD_INT = new MArithmetic("plus", "+", true, MNumeric.INT, MNumeric.BIGINT.instance(12)) {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output) {
            long a0 = inputs.get(0).getInt32();
            long a1 = inputs.get(1).getInt32();       
            output.putInt64(a0 + a1);
        }
    };

    public static final TOverload ADD_BIGINT = new MArithmetic("plus", "+", true, MNumeric.BIGINT, MNumeric.BIGINT.instance(21)) {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output) {
            long a0 = inputs.get(0).getInt64();
            long a1 = inputs.get(1).getInt64();
            output.putInt64(a0 + a1);
        }
    };

    public static final TOverload ADD_DECIMAL = new DecimalArithmetic("plus", "+", true) {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output) {
            output.putObject(getWrapper(context)
                        .add(MBigDecimal.getWrapper(inputs.get(0), context.inputTInstanceAt(1)))
                        .add(MBigDecimal.getWrapper(inputs.get(1), context.inputTInstanceAt(1))));
        }

        @Override
        protected long precisionAndScale(int arg0Precision, int arg0Scale, int arg1Precision, int arg1Scale) {
            return plusOrMinusArithmetic(arg0Precision, arg0Scale, arg1Precision, arg1Scale);
        }
    };
    
    // Subtract functions
    public static final TOverload SUBTRACT_TINYINT = new MArithmetic("minus", "-", false, MNumeric.TINYINT, MNumeric.INT.instance(5)) {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output) {
            int a0 = inputs.get(0).getInt8();
            int a1 = inputs.get(0).getInt8();
            output.putInt32(a0 - a1);
        }
    };

    public static final TOverload SUBTRACT_SMALLINT = new MArithmetic("minus", "-", false, MNumeric.SMALLINT, MNumeric.MEDIUMINT.instance(7)) {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output) {
            int a0 = inputs.get(0).getInt16();
            int a1 = inputs.get(0).getInt16();
            output.putInt32(a0 - a1);
        }
    };

    public static final TOverload SUBTRACT_MEDIUMINT = new MArithmetic("minus", "-", false, MNumeric.MEDIUMINT, MNumeric.INT.instance(9)) {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output) {
            int a0 = inputs.get(0).getInt32();
            int a1 = inputs.get(1).getInt32();       
            output.putInt32(a0 - a1);
        }
    };

    public static final TOverload SUBTRACT_INT = new MArithmetic("minus", "-", false, MNumeric.INT, MNumeric.BIGINT.instance(12)) {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output) {
            long a0 = inputs.get(0).getInt32();
            long a1 = inputs.get(1).getInt32();       
            output.putInt64(a0 - a1);
        }
    };

    public static final TOverload SUBTRACT_BIGINT = new MArithmetic("minus", "-", false, MNumeric.BIGINT, MNumeric.BIGINT.instance(21)) {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output) {
            long a0 = inputs.get(0).getInt64();
            long a1 = inputs.get(1).getInt64();
            output.putInt64(a0 - a1);
        }
    };

    public static final TOverload SUBTRACT_DECIMAL = new DecimalArithmetic("minus", "-", false) {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output) {
            output.putObject(getWrapper(context)
                        .add(MBigDecimal.getWrapper(inputs.get(0), context.inputTInstanceAt(1)))
                        .subtract(MBigDecimal.getWrapper(inputs.get(1), context.inputTInstanceAt(1))));
        }

        @Override
        protected long precisionAndScale(int arg0Precision, int arg0Scale, int arg1Precision, int arg1Scale) {
            return plusOrMinusArithmetic(arg0Precision, arg0Scale, arg1Precision, arg1Scale);
        }
    };
    
    // (Regular) Divide functions
    public static final TOverload DIVIDE_TINYINT = new MArithmetic("divide", "/", false, MNumeric.TINYINT, MApproximateNumber.DOUBLE.instance())
    {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output)
        {
            int divisor = inputs.get(1).getInt8();
            if (divisor == 0)
                output.putNull();
            else
                output.putDouble((double)inputs.get(0).getInt8() / divisor);
        }
    };

    public static final TOverload DIVIDE_SMALLINT = new MArithmetic("divide", "/", false, MNumeric.SMALLINT, MApproximateNumber.DOUBLE.instance())
    {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output)
        {
            int divisor = inputs.get(1).getInt16();
            if (divisor == 0)
                output.putNull();
            else
                output.putDouble((double)inputs.get(0).getInt16() / divisor);
        }
    };

    public static final TOverload DIVIDE_INT = new MArithmetic("divide", "/", false, MNumeric.INT, MApproximateNumber.DOUBLE.instance())
    {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output)
        {
            int divisor = inputs.get(1).getInt32();
            if (divisor == 0L)
                output.putNull();
            else
                output.putDouble((double)inputs.get(0).getInt32() / divisor);
        }
    };

    public static final TOverload DIVIDE_BIGINT = new MArithmetic("divide", "/", false, MNumeric.BIGINT, MApproximateNumber.DOUBLE.instance())
    {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output)
        {
            long divisor = inputs.get(1).getInt64();
            if (divisor == 0L)
                output.putNull();
            else
                output.putDouble((double)inputs.get(0).getInt64() / divisor);
        }
    };

    public static final TOverload DIVIDE_DOUBLE = new MArithmetic("divide", "/", false, MApproximateNumber.DOUBLE, MApproximateNumber.DOUBLE.instance())
    {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output)
        {
            double divisor = inputs.get(1).getDouble();
            if (Double.compare(divisor, 0) == 0)
                output.putNull();
            else
                output.putDouble(inputs.get(0).getDouble() / divisor);
        }
    };

    public static final TOverload DIVIDE_DECIMAL = new DecimalArithmetic("divide", "/", false) {
        @Override 
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output)
        {
            BigDecimalWrapper divisor = MBigDecimal.getWrapper(inputs.get(1), context.inputTInstanceAt(1));
            
            if (divisor.isZero())
                output.putNull();
            else
                output.putObject(getWrapper(context)
                                    .add(MBigDecimal.getWrapper(inputs.get(0), context.inputTInstanceAt(1)))
                                    .divide(divisor,
                                            context.outputTInstance().
                                                attribute(MBigDecimal.Attrs.SCALE)));  // get the scale computed
                                                                                       // during expr generation time
        }

       @Override
       protected long precisionAndScale(int p1, int s1, int p2, int s2) 
       {
           // http://msdn.microsoft.com/en-us/library/ms190476%28v=SQL.90%29.aspx
           
           //precision: p1 - s1 + s2 + max(6, s1 + p2 + 1) 
           // scale: max(6, s1 + p2 + 1)
           
           int precision = p1 - s1 + p2 + Math.max(6, s1 + p2 + 1);
           int scale = Math.max(6, s1 + p2 + 1);

           return packPrecisionAndScale(precision, scale);
       }
   };
    
   // integer division
    public static final TOverload DIV_TINYINT = new MArithmetic("div", "div", false, MNumeric.TINYINT, MNumeric.INT.instance(4))
    {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output)
        {
            int divisor = inputs.get(1).getInt8();
            if (divisor == 0)
                output.putNull();
            else
                output.putInt32(inputs.get(0).getInt8() / divisor);
        }
    };
   
    public static final TOverload DIV_SMALLINT = new MArithmetic("div", "div", false, MNumeric.SMALLINT, MNumeric.INT.instance(6))
    {

        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output)
        {
            int divisor = inputs.get(1).getInt16();
            if (divisor == 0)
                output.putNull();
            else
                output.putInt32(inputs.get(0).getInt16() / divisor);
        }
    };
 
    public static final TOverload DIV_MEDIUMINT = new MArithmetic("div", "div", false, MNumeric.MEDIUMINT, MNumeric.INT.instance(9))
    {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output)
        {
            int divisor = inputs.get(1).getInt32();
            if (divisor == 0)
                output.putNull();
            else
                output.putInt32(inputs.get(0).getInt32() / divisor);
        }
    };
   
    public static final TOverload DIV_INT =new MArithmetic("div", "div", false, MNumeric.INT, MNumeric.INT.instance(11))
    {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output)
        {
            int divisor = inputs.get(1).getInt32();
            if (divisor == 0)
                output.putNull();
            else
                output.putInt32(inputs.get(0).getInt32() / divisor);
        }
    };
   
    public static final TOverload DIV_BIGINT = new MArithmetic("div", "div", false, MNumeric.BIGINT, MNumeric.BIGINT.instance(20))
    {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output)
        {
            long divisor = inputs.get(1).getInt64();
            if (divisor == 0L)
                output.putNull();
            else
                output.putInt64(inputs.get(0).getInt64() / divisor);
        }
    };
     
    public static final TOverload DIV_DOUBLE = new MArithmetic("div", "div", false, MApproximateNumber.DOUBLE, MNumeric.BIGINT.instance(22))
    {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output)
        {
            double divisor = inputs.get(1).getDouble();
            if (Double.compare(divisor, 0) == 0)
                output.putNull();
            else
                output.putInt64((long)(inputs.get(0).getDouble() / divisor));
        }   
    };
    //(String overloadName, String infix, boolean associative, TClass inputType, TInstance resultType)
    public static final TOverload DIV_DECIMAL = new MArithmetic("div", "div", false, MNumeric.DECIMAL, null) {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs,
                                  PValueTarget output) {
            BigDecimalWrapper numerator = MBigDecimal.getWrapper(inputs.get(0), context.inputTInstanceAt(0));
            BigDecimalWrapper divisor = MBigDecimal.getWrapper(inputs.get(1), context.inputTInstanceAt(1));
            long rounded = numerator.divide(divisor).round(0).asBigDecimal().longValue();
            output.putInt64(rounded);
        }

        @Override
        public TOverloadResult resultType() {
            return TOverloadResult.custom(MNumeric.BIGINT.instance(), new TCustomOverloadResult() {
                @Override
                public TInstance resultInstance(List<TPreptimeValue> inputs, TPreptimeContext context) {
                    TInstance numeratorType = inputs.get(0).instance();
                    int precision = numeratorType.attribute(Attrs.PRECISION);
                    int scale = numeratorType.attribute(Attrs.SCALE);

                    // These seem to be MySQL's wonky rules. For instance:
                    //  DECIMAL(11, 0) -> BIGINT(12)
                    //  DECIMAL(11, 1) -> BIGINT(12)
                    //  DECIMAL(11, 2) -> BIGINT(11)
                    //  DECIMAL(11, 3) -> BIGINT(10) etc
                    ++precision;
                    scale = (scale == 0) ? 0 : scale - 1;
                    TClass tClass = (scale > 11) ? MNumeric.BIGINT : MNumeric.INT;
                    return tClass.instance(precision - scale);
                }
            });
        }
    };
   
    // Multiply functions
    public static final TOverload MULTIPLY_TINYINT = new MArithmetic("times", "*", true, MNumeric.TINYINT, MNumeric.INT.instance(7)) {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output) {
            int a0 = inputs.get(0).getInt8();
            int a1 = inputs.get(1).getInt8();
            output.putInt32(a0 * a1);
        }
    };

    public static final TOverload MULTIPLY_SMALLINT = new MArithmetic("times", "*", true, MNumeric.SMALLINT, MNumeric.INT.instance(11)) {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output) {
            int a0 = inputs.get(0).getInt16();
            int a1 = inputs.get(1).getInt16();
            output.putInt32(a0 * a1);
        }
    };

    public static final TOverload MULTIPLY_MEDIUMINT = new MArithmetic("times", "*", true, MNumeric.MEDIUMINT, MNumeric.BIGINT.instance(15)) {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output) {
            long a0 = inputs.get(0).getInt32();
            long a1 = inputs.get(1).getInt32();
            output.putInt64(a0 * a1);
        }
    };
    
    public static final TOverload MULTIPLY_INT = new MArithmetic("times", "*", true, MNumeric.INT, MNumeric.BIGINT.instance(21)) {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output) {
            long a0 = inputs.get(0).getInt32();
            long a1 = inputs.get(1).getInt32();
            output.putInt64(a0 * a1);
        }
    };
    
    public static final TOverload MULTIPLY_BIGINT = new MArithmetic("times", "*", true, MNumeric.BIGINT, MNumeric.BIGINT.instance(39)) {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output) {
            long a0 = inputs.get(0).getInt64();
            long a1 = inputs.get(1).getInt64();
            output.putInt64(a0 * a1);
        }
    };
    
    public static final TOverload MULTIPLY_DECIMAL = new DecimalArithmetic("times", "*", true) { // TODO --> What's the status of this TODO? (08/14/12)
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output) {
            long a0 = inputs.get(0).getInt64();
            long a1 = inputs.get(1).getInt64();
            output.putInt64(a0 * a1);
        }

       @Override
       protected long precisionAndScale(int arg0Precision, int arg0Scale, int arg1Precision, int arg1Scale) {
           return packPrecisionAndScale(arg0Precision + arg1Precision, arg0Scale + arg1Scale);
       }
    };

    public static final TOverload MOD_TINYTINT = new MArithmetic("mod", "mod", false, MNumeric.TINYINT, MNumeric.INT.instance(4))
    {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output)
        {
            int right = inputs.get(1).getInt8();
            if (right == 0)
                output.putNull();
            else
                output.putInt32(inputs.get(0).getInt8() % right);
        }
    };
   
    public static final TOverload MOD_SMALLINT = new MArithmetic("mod", "mod", false, MNumeric.SMALLINT, MNumeric.INT.instance(6))
    {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output)
        {
            int right = inputs.get(1).getInt16();
            if (right == 0)
                output.putNull();
            else
                output.putInt32(inputs.get(0).getInt16() % right);
        }
    };
   
    public static final TOverload MOD_MEDIUMINT = new MArithmetic("mod", "mod", false, MNumeric.MEDIUMINT, MNumeric.INT.instance(9))
    {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output)
        {
            int right = inputs.get(1).getInt32();
            if (right == 0)
                output.putNull();
            else
                output.putInt32(inputs.get(0).getInt32() % right);
        }
    };
   
    public static final TOverload MOD_INT = new MArithmetic("mod", "mod", false, MNumeric.INT, MNumeric.INT.instance(11))
    {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output)
        {
            int right = inputs.get(1).getInt32();
            if (right == 0)
                output.putNull();
            else
                output.putInt32(inputs.get(0).getInt32() % right);
        }
    };
   
    public static final TOverload MOD_BIGINT = new MArithmetic("mod", "mod", false, MNumeric.BIGINT, MNumeric.BIGINT.instance(20))
    {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output)
        {
            long right = inputs.get(1).getInt64();
            if (right == 0L)
                output.putNull();
            else
                output.putInt64(inputs.get(0).getInt64() % right);
        }
    };
   
    public static final TOverload MOD_DOUBLE = new MArithmetic("mod", "mod", false, MApproximateNumber.DOUBLE, MApproximateNumber.DOUBLE.instance())
    {
        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output)
        {
            double right = inputs.get(1).getDouble();
            if (Double.compare(right, 0) == 0)
                output.putNull();
            else
                output.putDouble(inputs.get(0).getDouble() % right);
        }
    };
   
    public static final TOverload MOD_DECIMAL = new DecimalArithmetic("mod", "mod", false)
    {
        @Override
        protected long precisionAndScale(int arg0Precision, int arg0Scale, int arg1Precision, int arg1Scale)
        {
            return packPrecisionAndScale(Math.max(arg0Precision, arg1Precision),
                                         Math.max(arg0Scale, arg1Scale));
        }

        @Override
        protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output)
        {            
             BigDecimalWrapper divisor = MBigDecimal.getWrapper(inputs.get(1), context.inputTInstanceAt(1));

             if (divisor.isZero())
                 output.putNull();
             else
                 output.putObject(getWrapper(context)
                                     .add(MBigDecimal.getWrapper(inputs.get(0), context.inputTInstanceAt(1)))
                                     .mod(divisor));
        }
    };
   
   // TODO this should extend some base class that MArithmetic also extends, rather than extending MArithmetic
   // but ignoring its TInstance field
    private abstract static class DecimalArithmetic extends MArithmetic {
        @Override
        public TOverloadResult resultType() {
           return TOverloadResult.custom(new TCustomOverloadResult() {
               @Override
               public TInstance resultInstance(List<TPreptimeValue> inputs, TPreptimeContext context) {
                   TInstance arg0 = inputs.get(0).instance();
                   TInstance arg1 = inputs.get(1).instance();

                   int arg0Precision = arg0.attribute(Attrs.PRECISION);
                   int arg0Scale = arg0.attribute(Attrs.SCALE);

                   int arg1Precision = arg1.attribute(Attrs.PRECISION);
                   int arg1Scale = arg1.attribute(Attrs.SCALE);
                   long resultPrecisionAndScale = precisionAndScale(arg0Precision, arg0Scale, arg1Precision, arg1Scale);
                   int resultPrecision = (int)(resultPrecisionAndScale >> 32);
                   int resultScale = (int)resultPrecisionAndScale;
                   return MNumeric.DECIMAL.instance(resultPrecision, resultScale);
               }
           });
        }

        protected abstract long precisionAndScale(int arg0Precision, int arg0Scale, int arg1Precision, int arg1Scale);

        static long packPrecisionAndScale(int precision, int scale) {
            long result = precision;
            result <<= 32;
            result |= scale;
            return result;
        }

        static long plusOrMinusArithmetic(int arg0Precision, int arg0Scale, int arg1Precision, int arg1Scale){
            int maxScale = Math.max(arg0Scale, arg1Precision);
            int maxPrecision = Math.max(arg0Precision, arg1Precision);
            return packPrecisionAndScale(maxPrecision + maxScale, maxScale);
        }

        protected DecimalArithmetic(String overloadName, String infix, boolean associative) {
            super(overloadName, infix, associative, MNumeric.DECIMAL, (TInstance) null);
        }
    }
}
