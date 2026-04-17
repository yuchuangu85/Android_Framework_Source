/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.health.connect.internal.datatypes;

import static android.health.connect.datatypes.MealType.MEAL_TYPE_UNKNOWN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.health.connect.datatypes.Identifier;
import android.health.connect.datatypes.MealType;
import android.health.connect.datatypes.NutritionRecord;
import android.health.connect.datatypes.RecordTypeIdentifier;
import android.health.connect.datatypes.units.Energy;
import android.health.connect.datatypes.units.Mass;
import android.os.Parcel;

/**
 * @hide
 * @see NutritionRecord
 */
@Identifier(recordIdentifier = RecordTypeIdentifier.RECORD_TYPE_NUTRITION)
public final class NutritionRecordInternal extends IntervalRecordInternal<NutritionRecord> {
    @Nullable private Double mUnsaturatedFat;
    @Nullable private Double mPotassium;
    @Nullable private Double mThiamin;
    @MealType.MealTypes private int mMealType = MEAL_TYPE_UNKNOWN;
    @Nullable private Double mTransFat;
    @Nullable private Double mManganese;
    @Nullable private Double mEnergyFromFat;
    @Nullable private Double mCaffeine;
    @Nullable private Double mDietaryFiber;
    @Nullable private Double mSelenium;
    @Nullable private Double mVitaminB6;
    @Nullable private Double mProtein;
    @Nullable private Double mChloride;
    @Nullable private Double mCholesterol;
    @Nullable private Double mCopper;
    @Nullable private Double mIodine;
    @Nullable private Double mVitaminB12;
    @Nullable private Double mZinc;
    @Nullable private Double mRiboflavin;
    @Nullable private Double mEnergy;
    @Nullable private Double mMolybdenum;
    @Nullable private Double mPhosphorus;
    @Nullable private Double mChromium;
    @Nullable private Double mTotalFat;
    @Nullable private Double mCalcium;
    @Nullable private Double mVitaminC;
    @Nullable private Double mVitaminE;
    @Nullable private Double mBiotin;
    @Nullable private Double mVitaminD;
    @Nullable private Double mNiacin;
    @Nullable private Double mMagnesium;
    @Nullable private Double mTotalCarbohydrate;
    @Nullable private Double mVitaminK;
    @Nullable private Double mPolyunsaturatedFat;
    @Nullable private Double mSaturatedFat;
    @Nullable private Double mSodium;
    @Nullable private Double mFolate;
    @Nullable private Double mMonounsaturatedFat;
    @Nullable private Double mPantothenicAcid;

    @Nullable private String mMealName;

    @Nullable private Double mIron;
    @Nullable private Double mVitaminA;
    @Nullable private Double mFolicAcid;
    @Nullable private Double mSugar;

    public NutritionRecordInternal() {
        super();
    }

    public NutritionRecordInternal(Parcel parcel) {
        super(parcel);
        mUnsaturatedFat = (Double) parcel.readValue(Double.class.getClassLoader());
        mPotassium = (Double) parcel.readValue(Double.class.getClassLoader());
        mThiamin = (Double) parcel.readValue(Double.class.getClassLoader());
        mMealType = parcel.readInt();
        mTransFat = (Double) parcel.readValue(Double.class.getClassLoader());
        mManganese = (Double) parcel.readValue(Double.class.getClassLoader());
        mEnergyFromFat = (Double) parcel.readValue(Double.class.getClassLoader());
        mCaffeine = (Double) parcel.readValue(Double.class.getClassLoader());
        mDietaryFiber = (Double) parcel.readValue(Double.class.getClassLoader());
        mSelenium = (Double) parcel.readValue(Double.class.getClassLoader());
        mVitaminB6 = (Double) parcel.readValue(Double.class.getClassLoader());
        mProtein = (Double) parcel.readValue(Double.class.getClassLoader());
        mChloride = (Double) parcel.readValue(Double.class.getClassLoader());
        mCholesterol = (Double) parcel.readValue(Double.class.getClassLoader());
        mCopper = (Double) parcel.readValue(Double.class.getClassLoader());
        mIodine = (Double) parcel.readValue(Double.class.getClassLoader());
        mVitaminB12 = (Double) parcel.readValue(Double.class.getClassLoader());
        mZinc = (Double) parcel.readValue(Double.class.getClassLoader());
        mRiboflavin = (Double) parcel.readValue(Double.class.getClassLoader());
        mEnergy = (Double) parcel.readValue(Double.class.getClassLoader());
        mMolybdenum = (Double) parcel.readValue(Double.class.getClassLoader());
        mPhosphorus = (Double) parcel.readValue(Double.class.getClassLoader());
        mChromium = (Double) parcel.readValue(Double.class.getClassLoader());
        mTotalFat = (Double) parcel.readValue(Double.class.getClassLoader());
        mCalcium = (Double) parcel.readValue(Double.class.getClassLoader());
        mVitaminC = (Double) parcel.readValue(Double.class.getClassLoader());
        mVitaminE = (Double) parcel.readValue(Double.class.getClassLoader());
        mBiotin = (Double) parcel.readValue(Double.class.getClassLoader());
        mVitaminD = (Double) parcel.readValue(Double.class.getClassLoader());
        mNiacin = (Double) parcel.readValue(Double.class.getClassLoader());
        mMagnesium = (Double) parcel.readValue(Double.class.getClassLoader());
        mTotalCarbohydrate = (Double) parcel.readValue(Double.class.getClassLoader());
        mVitaminK = (Double) parcel.readValue(Double.class.getClassLoader());
        mPolyunsaturatedFat = (Double) parcel.readValue(Double.class.getClassLoader());
        mSaturatedFat = (Double) parcel.readValue(Double.class.getClassLoader());
        mSodium = (Double) parcel.readValue(Double.class.getClassLoader());
        mFolate = (Double) parcel.readValue(Double.class.getClassLoader());
        mMonounsaturatedFat = (Double) parcel.readValue(Double.class.getClassLoader());
        mPantothenicAcid = (Double) parcel.readValue(Double.class.getClassLoader());
        mMealName = parcel.readString();
        mIron = (Double) parcel.readValue(Double.class.getClassLoader());
        mVitaminA = (Double) parcel.readValue(Double.class.getClassLoader());
        mFolicAcid = (Double) parcel.readValue(Double.class.getClassLoader());
        mSugar = (Double) parcel.readValue(Double.class.getClassLoader());
    }

    @Nullable
    public Double getUnsaturatedFat() {
        return mUnsaturatedFat;
    }

    /** returns this object with the specified unsaturatedFat */
    @NonNull
    public NutritionRecordInternal setUnsaturatedFat(double unsaturatedFat) {
        this.mUnsaturatedFat = unsaturatedFat;
        return this;
    }

    @Nullable
    public Double getPotassium() {
        return mPotassium;
    }

    /** returns this object with the specified potassium */
    @NonNull
    public NutritionRecordInternal setPotassium(double potassium) {
        this.mPotassium = potassium;
        return this;
    }

    @Nullable
    public Double getThiamin() {
        return mThiamin;
    }

    /** returns this object with the specified thiamin */
    @NonNull
    public NutritionRecordInternal setThiamin(double thiamin) {
        this.mThiamin = thiamin;
        return this;
    }

    @MealType.MealTypes
    public int getMealType() {
        return mMealType;
    }

    /** returns this object with the specified mealType */
    @NonNull
    public NutritionRecordInternal setMealType(@MealType.MealTypes int mealType) {
        this.mMealType = mealType;
        return this;
    }

    @Nullable
    public Double getTransFat() {
        return mTransFat;
    }

    /** returns this object with the specified transFat */
    @NonNull
    public NutritionRecordInternal setTransFat(double transFat) {
        this.mTransFat = transFat;
        return this;
    }

    @Nullable
    public Double getManganese() {
        return mManganese;
    }

    /** returns this object with the specified manganese */
    @NonNull
    public NutritionRecordInternal setManganese(double manganese) {
        this.mManganese = manganese;
        return this;
    }

    @Nullable
    public Double getEnergyFromFat() {
        return mEnergyFromFat;
    }

    /** returns this object with the specified energyFromFat */
    @NonNull
    public NutritionRecordInternal setEnergyFromFat(double energyFromFat) {
        this.mEnergyFromFat = energyFromFat;
        return this;
    }

    @Nullable
    public Double getCaffeine() {
        return mCaffeine;
    }

    /** returns this object with the specified caffeine */
    @NonNull
    public NutritionRecordInternal setCaffeine(double caffeine) {
        this.mCaffeine = caffeine;
        return this;
    }

    @Nullable
    public Double getDietaryFiber() {
        return mDietaryFiber;
    }

    /** returns this object with the specified dietaryFiber */
    @NonNull
    public NutritionRecordInternal setDietaryFiber(double dietaryFiber) {
        this.mDietaryFiber = dietaryFiber;
        return this;
    }

    @Nullable
    public Double getSelenium() {
        return mSelenium;
    }

    /** returns this object with the specified selenium */
    @NonNull
    public NutritionRecordInternal setSelenium(double selenium) {
        this.mSelenium = selenium;
        return this;
    }

    @Nullable
    public Double getVitaminB6() {
        return mVitaminB6;
    }

    /** returns this object with the specified vitaminB6 */
    @NonNull
    public NutritionRecordInternal setVitaminB6(double vitaminB6) {
        this.mVitaminB6 = vitaminB6;
        return this;
    }

    @Nullable
    public Double getProtein() {
        return mProtein;
    }

    /** returns this object with the specified protein */
    @NonNull
    public NutritionRecordInternal setProtein(double protein) {
        this.mProtein = protein;
        return this;
    }

    @Nullable
    public Double getChloride() {
        return mChloride;
    }

    /** returns this object with the specified chloride */
    @NonNull
    public NutritionRecordInternal setChloride(double chloride) {
        this.mChloride = chloride;
        return this;
    }

    @Nullable
    public Double getCholesterol() {
        return mCholesterol;
    }

    /** returns this object with the specified cholesterol */
    @NonNull
    public NutritionRecordInternal setCholesterol(double cholesterol) {
        this.mCholesterol = cholesterol;
        return this;
    }

    @Nullable
    public Double getCopper() {
        return mCopper;
    }

    /** returns this object with the specified copper */
    @NonNull
    public NutritionRecordInternal setCopper(double copper) {
        this.mCopper = copper;
        return this;
    }

    @Nullable
    public Double getIodine() {
        return mIodine;
    }

    /** returns this object with the specified iodine */
    @NonNull
    public NutritionRecordInternal setIodine(double iodine) {
        this.mIodine = iodine;
        return this;
    }

    @Nullable
    public Double getVitaminB12() {
        return mVitaminB12;
    }

    /** returns this object with the specified vitaminB12 */
    @NonNull
    public NutritionRecordInternal setVitaminB12(double vitaminB12) {
        this.mVitaminB12 = vitaminB12;
        return this;
    }

    @Nullable
    public Double getZinc() {
        return mZinc;
    }

    /** returns this object with the specified zinc */
    @NonNull
    public NutritionRecordInternal setZinc(double zinc) {
        this.mZinc = zinc;
        return this;
    }

    @Nullable
    public Double getRiboflavin() {
        return mRiboflavin;
    }

    /** returns this object with the specified riboflavin */
    @NonNull
    public NutritionRecordInternal setRiboflavin(double riboflavin) {
        this.mRiboflavin = riboflavin;
        return this;
    }

    @Nullable
    public Double getEnergy() {
        return mEnergy;
    }

    /** returns this object with the specified energy */
    @NonNull
    public NutritionRecordInternal setEnergy(double energy) {
        this.mEnergy = energy;
        return this;
    }

    @Nullable
    public Double getMolybdenum() {
        return mMolybdenum;
    }

    /** returns this object with the specified molybdenum */
    @NonNull
    public NutritionRecordInternal setMolybdenum(double molybdenum) {
        this.mMolybdenum = molybdenum;
        return this;
    }

    @Nullable
    public Double getPhosphorus() {
        return mPhosphorus;
    }

    /** returns this object with the specified phosphorus */
    @NonNull
    public NutritionRecordInternal setPhosphorus(double phosphorus) {
        this.mPhosphorus = phosphorus;
        return this;
    }

    @Nullable
    public Double getChromium() {
        return mChromium;
    }

    /** returns this object with the specified chromium */
    @NonNull
    public NutritionRecordInternal setChromium(double chromium) {
        this.mChromium = chromium;
        return this;
    }

    @Nullable
    public Double getTotalFat() {
        return mTotalFat;
    }

    /** returns this object with the specified totalFat */
    @NonNull
    public NutritionRecordInternal setTotalFat(double totalFat) {
        this.mTotalFat = totalFat;
        return this;
    }

    @Nullable
    public Double getCalcium() {
        return mCalcium;
    }

    /** returns this object with the specified calcium */
    @NonNull
    public NutritionRecordInternal setCalcium(double calcium) {
        this.mCalcium = calcium;
        return this;
    }

    @Nullable
    public Double getVitaminC() {
        return mVitaminC;
    }

    /** returns this object with the specified vitaminC */
    @NonNull
    public NutritionRecordInternal setVitaminC(double vitaminC) {
        this.mVitaminC = vitaminC;
        return this;
    }

    @Nullable
    public Double getVitaminE() {
        return mVitaminE;
    }

    /** returns this object with the specified vitaminE */
    @NonNull
    public NutritionRecordInternal setVitaminE(double vitaminE) {
        this.mVitaminE = vitaminE;
        return this;
    }

    @Nullable
    public Double getBiotin() {
        return mBiotin;
    }

    /** returns this object with the specified biotin */
    @NonNull
    public NutritionRecordInternal setBiotin(double biotin) {
        this.mBiotin = biotin;
        return this;
    }

    @Nullable
    public Double getVitaminD() {
        return mVitaminD;
    }

    /** returns this object with the specified vitaminD */
    @NonNull
    public NutritionRecordInternal setVitaminD(double vitaminD) {
        this.mVitaminD = vitaminD;
        return this;
    }

    @Nullable
    public Double getNiacin() {
        return mNiacin;
    }

    /** returns this object with the specified niacin */
    @NonNull
    public NutritionRecordInternal setNiacin(double niacin) {
        this.mNiacin = niacin;
        return this;
    }

    @Nullable
    public Double getMagnesium() {
        return mMagnesium;
    }

    /** returns this object with the specified magnesium */
    @NonNull
    public NutritionRecordInternal setMagnesium(double magnesium) {
        this.mMagnesium = magnesium;
        return this;
    }

    @Nullable
    public Double getTotalCarbohydrate() {
        return mTotalCarbohydrate;
    }

    /** returns this object with the specified totalCarbohydrate */
    @NonNull
    public NutritionRecordInternal setTotalCarbohydrate(double totalCarbohydrate) {
        this.mTotalCarbohydrate = totalCarbohydrate;
        return this;
    }

    @Nullable
    public Double getVitaminK() {
        return mVitaminK;
    }

    /** returns this object with the specified vitaminK */
    @NonNull
    public NutritionRecordInternal setVitaminK(double vitaminK) {
        this.mVitaminK = vitaminK;
        return this;
    }

    @Nullable
    public Double getPolyunsaturatedFat() {
        return mPolyunsaturatedFat;
    }

    /** returns this object with the specified polyunsaturatedFat */
    @NonNull
    public NutritionRecordInternal setPolyunsaturatedFat(double polyunsaturatedFat) {
        this.mPolyunsaturatedFat = polyunsaturatedFat;
        return this;
    }

    @Nullable
    public Double getSaturatedFat() {
        return mSaturatedFat;
    }

    /** returns this object with the specified saturatedFat */
    @NonNull
    public NutritionRecordInternal setSaturatedFat(double saturatedFat) {
        this.mSaturatedFat = saturatedFat;
        return this;
    }

    @Nullable
    public Double getSodium() {
        return mSodium;
    }

    /** returns this object with the specified sodium */
    @NonNull
    public NutritionRecordInternal setSodium(double sodium) {
        this.mSodium = sodium;
        return this;
    }

    @Nullable
    public Double getFolate() {
        return mFolate;
    }

    /** returns this object with the specified folate */
    @NonNull
    public NutritionRecordInternal setFolate(double folate) {
        this.mFolate = folate;
        return this;
    }

    @Nullable
    public Double getMonounsaturatedFat() {
        return mMonounsaturatedFat;
    }

    /** returns this object with the specified monounsaturatedFat */
    @NonNull
    public NutritionRecordInternal setMonounsaturatedFat(double monounsaturatedFat) {
        this.mMonounsaturatedFat = monounsaturatedFat;
        return this;
    }

    @Nullable
    public Double getPantothenicAcid() {
        return mPantothenicAcid;
    }

    /** returns this object with the specified pantothenicAcid */
    @NonNull
    public NutritionRecordInternal setPantothenicAcid(double pantothenicAcid) {
        this.mPantothenicAcid = pantothenicAcid;
        return this;
    }

    @Nullable
    public String getMealName() {
        return mMealName;
    }

    /** returns this object with the specified name */
    @NonNull
    public NutritionRecordInternal setMealName(@Nullable String mealName) {
        this.mMealName = mealName;
        return this;
    }

    @Nullable
    public Double getIron() {
        return mIron;
    }

    /** returns this object with the specified iron */
    @NonNull
    public NutritionRecordInternal setIron(double iron) {
        this.mIron = iron;
        return this;
    }

    @Nullable
    public Double getVitaminA() {
        return mVitaminA;
    }

    /** returns this object with the specified vitaminA */
    @NonNull
    public NutritionRecordInternal setVitaminA(double vitaminA) {
        this.mVitaminA = vitaminA;
        return this;
    }

    @Nullable
    public Double getFolicAcid() {
        return mFolicAcid;
    }

    /** returns this object with the specified folicAcid */
    @NonNull
    public NutritionRecordInternal setFolicAcid(double folicAcid) {
        this.mFolicAcid = folicAcid;
        return this;
    }

    @Nullable
    public Double getSugar() {
        return mSugar;
    }

    /** returns this object with the specified sugar */
    @NonNull
    public NutritionRecordInternal setSugar(double sugar) {
        this.mSugar = sugar;
        return this;
    }

    @NonNull
    @Override
    public NutritionRecord toExternalRecord() {
        NutritionRecord.Builder builder =
                new NutritionRecord.Builder(buildMetaData(), getStartTime(), getEndTime())
                        .setMealType(getMealType())
                        .setStartZoneOffset(getStartZoneOffset())
                        .setEndZoneOffset(getEndZoneOffset());
        if (getUnsaturatedFat() != null) {
            builder.setUnsaturatedFat(Mass.fromGrams(getUnsaturatedFat()));
        }
        if (getPotassium() != null) {
            builder.setPotassium(Mass.fromGrams(getPotassium()));
        }
        if (getThiamin() != null) {
            builder.setThiamin(Mass.fromGrams(getThiamin()));
        }
        if (getTransFat() != null) {
            builder.setTransFat(Mass.fromGrams(getTransFat()));
        }
        if (getManganese() != null) {
            builder.setManganese(Mass.fromGrams(getManganese()));
        }
        if (getEnergyFromFat() != null) {
            builder.setEnergyFromFat(Energy.fromCalories(getEnergyFromFat()));
        }
        if (getCaffeine() != null) {
            builder.setCaffeine(Mass.fromGrams(getCaffeine()));
        }
        if (getDietaryFiber() != null) {
            builder.setDietaryFiber(Mass.fromGrams(getDietaryFiber()));
        }
        if (getSelenium() != null) {
            builder.setSelenium(Mass.fromGrams(getSelenium()));
        }
        if (getVitaminB6() != null) {
            builder.setVitaminB6(Mass.fromGrams(getVitaminB6()));
        }
        if (getProtein() != null) {
            builder.setProtein(Mass.fromGrams(getProtein()));
        }
        if (getChloride() != null) {
            builder.setChloride(Mass.fromGrams(getChloride()));
        }
        if (getCholesterol() != null) {
            builder.setCholesterol(Mass.fromGrams(getCholesterol()));
        }
        if (getCopper() != null) {
            builder.setCopper(Mass.fromGrams(getCopper()));
        }
        if (getIodine() != null) {
            builder.setIodine(Mass.fromGrams(getIodine()));
        }
        if (getVitaminB12() != null) {
            builder.setVitaminB12(Mass.fromGrams(getVitaminB12()));
        }
        if (getZinc() != null) {
            builder.setZinc(Mass.fromGrams(getZinc()));
        }
        if (getRiboflavin() != null) {
            builder.setRiboflavin(Mass.fromGrams(getRiboflavin()));
        }
        if (getEnergy() != null) {
            builder.setEnergy(Energy.fromCalories(getEnergy()));
        }
        if (getMolybdenum() != null) {
            builder.setMolybdenum(Mass.fromGrams(getMolybdenum()));
        }
        if (getPhosphorus() != null) {
            builder.setPhosphorus(Mass.fromGrams(getPhosphorus()));
        }
        if (getChromium() != null) {
            builder.setChromium(Mass.fromGrams(getChromium()));
        }
        if (getTotalFat() != null) {
            builder.setTotalFat(Mass.fromGrams(getTotalFat()));
        }
        if (getCalcium() != null) {
            builder.setCalcium(Mass.fromGrams(getCalcium()));
        }
        if (getVitaminC() != null) {
            builder.setVitaminC(Mass.fromGrams(getVitaminC()));
        }
        if (getVitaminE() != null) {
            builder.setVitaminE(Mass.fromGrams(getVitaminE()));
        }
        if (getBiotin() != null) {
            builder.setBiotin(Mass.fromGrams(getBiotin()));
        }
        if (getVitaminD() != null) {
            builder.setVitaminD(Mass.fromGrams(getVitaminD()));
        }
        if (getNiacin() != null) {
            builder.setNiacin(Mass.fromGrams(getNiacin()));
        }
        if (getMagnesium() != null) {
            builder.setMagnesium(Mass.fromGrams(getMagnesium()));
        }
        if (getTotalCarbohydrate() != null) {
            builder.setTotalCarbohydrate(Mass.fromGrams(getTotalCarbohydrate()));
        }
        if (getVitaminK() != null) {
            builder.setVitaminK(Mass.fromGrams(getVitaminK()));
        }
        if (getPolyunsaturatedFat() != null) {
            builder.setPolyunsaturatedFat(Mass.fromGrams(getPolyunsaturatedFat()));
        }
        if (getSaturatedFat() != null) {
            builder.setSaturatedFat(Mass.fromGrams(getSaturatedFat()));
        }
        if (getSodium() != null) {
            builder.setSodium(Mass.fromGrams(getSodium()));
        }
        if (getFolate() != null) {
            builder.setFolate(Mass.fromGrams(getFolate()));
        }
        if (getMonounsaturatedFat() != null) {
            builder.setMonounsaturatedFat(Mass.fromGrams(getMonounsaturatedFat()));
        }
        if (getPantothenicAcid() != null) {
            builder.setPantothenicAcid(Mass.fromGrams(getPantothenicAcid()));
        }
        if (getIron() != null) {
            builder.setIron(Mass.fromGrams(getIron()));
        }
        if (getVitaminA() != null) {
            builder.setVitaminA(Mass.fromGrams(getVitaminA()));
        }
        if (getFolicAcid() != null) {
            builder.setFolicAcid(Mass.fromGrams(getFolicAcid()));
        }
        if (getSugar() != null) {
            builder.setSugar(Mass.fromGrams(getSugar()));
        }
        // Even though mealName can be null in NutritionRecord, it cannot be set to null in the
        // builder.
        String mealName = getMealName();
        if (mealName != null) {
            builder.setMealName(mealName);
        }
        return builder.buildWithoutValidation();
    }

    @Override
    void populateIntervalRecordTo(@NonNull Parcel parcel) {
        parcel.writeValue(mUnsaturatedFat);
        parcel.writeValue(mPotassium);
        parcel.writeValue(mThiamin);
        parcel.writeInt(mMealType);
        parcel.writeValue(mTransFat);
        parcel.writeValue(mManganese);
        parcel.writeValue(mEnergyFromFat);
        parcel.writeValue(mCaffeine);
        parcel.writeValue(mDietaryFiber);
        parcel.writeValue(mSelenium);
        parcel.writeValue(mVitaminB6);
        parcel.writeValue(mProtein);
        parcel.writeValue(mChloride);
        parcel.writeValue(mCholesterol);
        parcel.writeValue(mCopper);
        parcel.writeValue(mIodine);
        parcel.writeValue(mVitaminB12);
        parcel.writeValue(mZinc);
        parcel.writeValue(mRiboflavin);
        parcel.writeValue(mEnergy);
        parcel.writeValue(mMolybdenum);
        parcel.writeValue(mPhosphorus);
        parcel.writeValue(mChromium);
        parcel.writeValue(mTotalFat);
        parcel.writeValue(mCalcium);
        parcel.writeValue(mVitaminC);
        parcel.writeValue(mVitaminE);
        parcel.writeValue(mBiotin);
        parcel.writeValue(mVitaminD);
        parcel.writeValue(mNiacin);
        parcel.writeValue(mMagnesium);
        parcel.writeValue(mTotalCarbohydrate);
        parcel.writeValue(mVitaminK);
        parcel.writeValue(mPolyunsaturatedFat);
        parcel.writeValue(mSaturatedFat);
        parcel.writeValue(mSodium);
        parcel.writeValue(mFolate);
        parcel.writeValue(mMonounsaturatedFat);
        parcel.writeValue(mPantothenicAcid);
        parcel.writeString(mMealName);
        parcel.writeValue(mIron);
        parcel.writeValue(mVitaminA);
        parcel.writeValue(mFolicAcid);
        parcel.writeValue(mSugar);
    }
}
