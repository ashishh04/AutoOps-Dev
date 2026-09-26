import React from "react";
import { Stack } from "@phosphor-icons/react";
import AegisVoice from "../components/AegisVoice";

export default function Hero() {
  const scrollToFeatures = () => {
    const el = document.getElementById('features');
    if (el) el.scrollIntoView({ behavior: 'smooth' });
  };

  const scrollToTechStack = () => {
    const el = document.getElementById('tech-stack');
    if (el) el.scrollIntoView({ behavior: 'smooth' });
  };

  return (
    <section id="top" className="grid-bg relative overflow-hidden pt-8 pb-20 lg:pt-12 lg:pb-24 min-h-[calc(100vh-80px)] flex items-center">
      <div className="px-6 max-w-7xl mx-auto relative z-10 w-full">
        <div className="flex flex-col lg:flex-row items-center justify-between gap-12 lg:gap-16">
          {/* Left Content */}
          <div className="flex-1 text-left">

            <h1 className="text-5xl sm:text-6xl md:text-[5.5rem] mb-6 tracking-tight leading-[1.15] text-slate-900 drop-shadow-sm" style={{ fontFamily: "'Playfair Display', serif" }}>
              Autonomous<br />
              Operations.<br />
              <span className="italic text-transparent bg-clip-text bg-gradient-to-r from-blue-600 via-indigo-600 to-cyan-500 drop-shadow-sm whitespace-nowrap">Governed Perfectly.</span>
            </h1>

            <p className="text-lg md:text-xl text-slate-600 max-w-xl mb-10 leading-relaxed font-medium transition-colors">
              Welcome to AutoOps — where every AI agent executes within your safe, scoped context. A warm, observable, deeply governed runtime for the enterprises that refuse to compromise.
            </p>

            <div className="flex flex-col sm:flex-row items-center gap-4 sm:gap-6 mb-8 lg:mb-0">
              <button onClick={scrollToFeatures} className="w-full sm:w-auto px-8 py-4 bg-blue-600 hover:bg-blue-700 text-white text-lg font-bold rounded-full transition-all flex items-center justify-center gap-3 hover:-translate-y-1 shadow-lg">
                Explore Capabilities
                <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><line x1="5" y1="12" x2="19" y2="12"></line><polyline points="12 5 19 12 12 19"></polyline></svg>
              </button>
            </div>
          </div>

          {/* Right Content: Smart Theme Robot */}
          <div className="flex-1 relative flex items-center justify-center min-h-[400px] lg:min-h-[500px] w-full">
            {/* Central Container for Perfect Alignment */}
            <div className="relative flex items-center justify-center w-[400px] h-[400px] lg:w-[450px] lg:h-[450px] lg:-translate-y-8">

              {/* Concentric Decorators */}
              <div className="absolute inset-0 rounded-full border border-slate-200 shadow-[inset_0_0_50px_rgba(0,0,0,0.02)] animate-spin-slow"></div>
              <div className="absolute inset-[60px] rounded-full border border-slate-200 shadow-sm animate-spin-slow" style={{ animationDirection: 'reverse' }}></div>

              <style>
                {`
                @keyframes smooth-float {
                  0%, 100% { transform: translateY(0px); }
                  50% { transform: translateY(-25px); }
                }
                .animate-smooth-float {
                  animation: smooth-float 6s ease-in-out infinite;
                }
                `}
              </style>

              {/* Theme Robot */}
              <img src="/assets/robot_blue_transparent.png?v=2" alt="AutoOps Mascot Light" className="absolute z-10 w-[420px] h-[420px] object-contain animate-smooth-float pointer-events-none drop-shadow-2xl" onError={(e) => { e.target.src = '/robot.png'; }} />

            </div>

            {/* Floating Chat Bubble — a live ElevenLabs conversation. Always
                shown; inert until the backend reports voice credentials. */}
            <AegisVoice />
            <div className="absolute -bottom-16 left-1/2 -translate-x-1/2 text-[9px] font-bold tracking-[0.2em] uppercase text-slate-400 z-10 whitespace-nowrap">
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}