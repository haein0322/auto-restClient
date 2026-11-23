import { create } from "zustand";
import { api, ApiError } from "@/lib/api";
import type { SpecDetail } from "@/lib/schemas";

/** 현재 작업 중인 명세와 로딩 상태. StepNav와 각 단계 화면이 공유한다. */
interface WorkflowState {
  detail: SpecDetail | null;
  loading: boolean;
  error: string | null;
  loadSpec: (id: number) => Promise<void>;
  reset: () => void;
}

export const useWorkflowStore = create<WorkflowState>((set) => ({
  detail: null,
  loading: false,
  error: null,

  loadSpec: async (id) => {
    set({ loading: true, error: null });
    try {
      const detail = await api.getSpec(id);
      set({ detail, loading: false });
    } catch (e) {
      const message = e instanceof ApiError ? e.message : "명세를 불러오지 못했습니다.";
      set({ detail: null, loading: false, error: message });
    }
  },

  reset: () => set({ detail: null, loading: false, error: null }),
}));
