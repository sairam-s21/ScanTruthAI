// src/firebase.ts
import { initializeApp } from "firebase/app";
import { getAuth, GoogleAuthProvider } from "firebase/auth";
import {
  initializeFirestore,
  persistentLocalCache,
  persistentMultipleTabManager,
} from "firebase/firestore";
import {
  initializeAppCheck,
  ReCaptchaEnterpriseProvider,
} from "firebase/app-check";
import {
  getAI,
  getGenerativeModel,
  GoogleAIBackend,
  InferenceMode,
  Schema,
} from "firebase/ai";

// ── TypeScript Interfaces Aligned to Figma UI ─────────────────────────────

export interface DetectedPiiItem {
  label: string;
  originalValue: string;
  maskedPreview: string;
  category: "Personal" | "Identifier";
}

export interface PrivacyGateResult {
  redactedText: string;
  redactedItemsCount: number;
  detectedItems: DetectedPiiItem[];
}

export interface HealthMarker {
  category: string;
  value: string;
  tone: "coral" | "green" | "blue";
  iconType: "glucose" | "allergy" | "heart" | "medication";
}

export interface ExtractedMedicalProfile {
  reportTitle: string;
  markersCount: number;
  healthMarkers: HealthMarker[];
  conditions: string[];
  allergies: string[];
  medications: string[];
  dietaryRestrictions: string[];
}

export interface HealthFactor {
  title: string;
  description: string;
  impactBadge: string;
  sentiment: "positive" | "warning" | "neutral";
}

export interface IngredientBreakdownItem {
  name: string;
  note: string;
  status: "Great" | "Okay" | "Caution" | "Avoid";
}

export interface IngredientAnalysisResult {
  productName: string;
  productCategory: "food" | "medicine";
  safetyScore: number; // 0-100 (70-100 Good match, 40-69 Use with care, 0-39 Avoid)
  badgeLabel: string;
  headline: string;
  aiSummary: string;
  healthFactors: HealthFactor[];
  ingredientBreakdown: IngredientBreakdownItem[];
  recommendedIntake: {
    dosageHeadline: string;
    guidance: string;
  };
}

// ── 1. Firebase Initialization ────────────────────────────────────────────

const firebaseConfig = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY,
  authDomain: "scantruthai.firebaseapp.com",
  projectId: "scantruthai",
  storageBucket: "scantruthai.firebasestorage.app",
  messagingSenderId: "593275600285",
  appId: "1:593275600285:web:3b97304646885318404374",
  measurementId: "G-487TT35SN9",
};

export const app = initializeApp(firebaseConfig);

// ── 2. App Check (Required for Firebase AI Logic) ─────────────────────────

if (import.meta.env.DEV) {
  (self as unknown as Record<string, unknown>).FIREBASE_APPCHECK_DEBUG_TOKEN =
    import.meta.env.VITE_APPCHECK_DEBUG_TOKEN || true;
}

export const appCheck = initializeAppCheck(app, {
  provider: new ReCaptchaEnterpriseProvider(
    import.meta.env.VITE_RECAPTCHA_SITE_KEY
  ),
  isTokenAutoRefreshEnabled: true,
});

// ── 3. Auth & Offline-First Firestore ─────────────────────────────────────

export const auth = getAuth(app);
export const googleProvider = new GoogleAuthProvider();

export const db = initializeFirestore(app, {
  localCache: persistentLocalCache({
    tabManager: persistentMultipleTabManager(),
  }),
});

// ── 4. Firebase AI Logic (Gemini Developer API) ───────────────────────────

export const ai = getAI(app, { backend: new GoogleAIBackend() });

const medicalProfileSchema = Schema.object({
  properties: {
    reportTitle: Schema.string(),
    markersCount: Schema.integer(),
    healthMarkers: Schema.array({
      items: Schema.object({
        properties: {
          category: Schema.string(),
          value: Schema.string(),
          tone: Schema.string(),
          iconType: Schema.string(),
        },
      }),
    }),
    conditions: Schema.array({ items: Schema.string() }),
    allergies: Schema.array({ items: Schema.string() }),
    medications: Schema.array({ items: Schema.string() }),
    dietaryRestrictions: Schema.array({ items: Schema.string() }),
  },
});

const ingredientAnalysisSchema = Schema.object({
  properties: {
    productName: Schema.string(),
    productCategory: Schema.string(),
    safetyScore: Schema.integer(),
    badgeLabel: Schema.string(),
    headline: Schema.string(),
    aiSummary: Schema.string(),
    healthFactors: Schema.array({
      items: Schema.object({
        properties: {
          title: Schema.string(),
          description: Schema.string(),
          impactBadge: Schema.string(),
          sentiment: Schema.string(),
        },
      }),
    }),
    ingredientBreakdown: Schema.array({
      items: Schema.object({
        properties: {
          name: Schema.string(),
          note: Schema.string(),
          status: Schema.string(),
        },
      }),
    }),
    recommendedIntake: Schema.object({
      properties: {
        dosageHeadline: Schema.string(),
        guidance: Schema.string(),
      },
    }),
  },
});

// Stage 2: Local Privacy Gate (Gemini Nano on-device)
export const getPrivacyGateModel = () =>
  getGenerativeModel(ai, {
    mode: InferenceMode.ONLY_ON_DEVICE,
    onDeviceParams: {
      createOptions: {
        expectedInputs: [{ type: "text" }],
      },
    },
  });

// Stage 3: Cloud Medical Profile & Health Note Extractor
export const getProfileExtractorModel = () =>
  getGenerativeModel(ai, {
    mode: InferenceMode.ONLY_IN_CLOUD,
    inCloudParams: {
      model: "gemini-3.5-flash-lite",
      generationConfig: {
        responseMimeType: "application/json",
        responseSchema: medicalProfileSchema,
      },
    },
  });

// Stage 4: Hybrid Ingredient Analyzer (Supports 'auto' | 'flash' | 'nano')
export const getIngredientAnalyzerModel = (
  mode: "auto" | "flash" | "nano" = "auto"
) => {
  if (mode === "nano") {
    return getGenerativeModel(ai, {
      mode: InferenceMode.ONLY_ON_DEVICE,
      onDeviceParams: {
        createOptions: {
          expectedInputs: [{ type: "text" }],
        },
      },
    });
  }

  return getGenerativeModel(ai, {
    mode:
      mode === "flash"
        ? InferenceMode.ONLY_IN_CLOUD
        : InferenceMode.PREFER_IN_CLOUD,
    inCloudParams: {
      model: "gemini-3.5-flash-lite",
      generationConfig: {
        responseMimeType: "application/json",
        responseSchema: ingredientAnalysisSchema,
      },
    },
  });
};