"use client";
import { useState, useRef, useEffect } from "react";
import { ConnectKitButton } from "connectkit";
import { useAccount } from "wagmi";
import { 
  Book, Music, Video, Languages, PenTool, Sparkles, 
  ChevronRight, Save, Settings, Maximize2, Minimize2, 
  MessageSquare, User, Zap, BookOpen, Send, Loader2, Info,
  Menu, X, CloudUpload, CheckCircle2, AlertCircle, Trash2, Edit3
} from "lucide-react";
import { uploadTextToIPFS } from "@/services/pinata";

export default function ScribUp() {
  const { isConnected, address } = useAccount();
  const [activeAgent, setActiveAgent] = useState("novelist");
  const [textContent, setTextContent] = useState("");
  const [isAiOpen, setIsAiOpen] = useState(false);
  const [isSidebarOpen, setIsSidebarOpen] = useState(false);
  const [isFocusMode, setIsFocusMode] = useState(false);
  const [messages, setMessages] = useState<{role: string, text: string}[]>([]);
  const [inputMessage, setInputMessage] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const [isUploading, setIsUploading] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [lastCid, setLastCid] = useState(null);
  const [saveSuccess, setSaveSuccess] = useState(false);
  const [userProjects, setUserProjects] = useState<any[]>([]);
  const chatEndRef = useRef<HTMLDivElement>(null);

  const agents = {
    novelist: { name: "Le Romancier", icon: <Book className="w-4 h-4" />, color: "#00f2ff", desc: "Narratologie & Style" },
    lyricist: { name: "Le Parolier", icon: <Music className="w-4 h-4" />, color: "#bc13fe", desc: "Rimes & MÃƒÂ©trique" },
    screenwriter: { name: "Le ScÃƒÂ©nariste", icon: <Video className="w-4 h-4" />, color: "#ffd700", desc: "Structure & Dialogue" },
    linguist: { name: "Le Linguiste", icon: <Languages className="w-4 h-4" />, color: "#00ff88", desc: "Contexte & Ãƒâ€°tymologie" }
  };

  useEffect(() => { chatEndRef.current?.scrollIntoView({ behavior: "smooth" }); }, [messages, isLoading]);

  const fetchProjects = async () => {
    if (!address) return;
    try {
      const response = await fetch(`/api/projects?userId=${address}`);
      const data = await response.json();
      setUserProjects(data);
    } catch (error) { console.error(error); }
  };

  useEffect(() => { fetchProjects(); }, [address]);

  const handleSendMessage = async () => {
    if (!inputMessage.trim() || isLoading) return;
    const userMsg = inputMessage;
    setInputMessage("");
    setMessages(prev => [...prev, { role: "user", text: userMsg }]);
    setIsLoading(true);
    try {
      const response = await fetch("/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: userMsg, agent: activeAgent, context: textContent.slice(-1000) }),      
      });
      const data = await response.json();
      setMessages(prev => [...prev, { role: "ai", text: data.text }]);
    } catch (error) { console.error(error); } finally { setIsLoading(false); }
  };

  const handleRailwaySave = async () => {
    if (!textContent.trim() || !address || isSaving) return;
    setIsSaving(true);
    try {
      const response = await fetch("/api/projects", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ userId: address, title: textContent.slice(0, 20) + "...", content: textContent, agent: activeAgent, cid: lastCid }),
      });
      if (response.ok) { setSaveSuccess(true); fetchProjects(); setTimeout(() => setSaveSuccess(false), 3000); }
    } catch (error) { console.error(error); } finally { setIsSaving(false); }
  };

  const handleDeleteProject = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    if (!confirm("Supprimer ?")) return;
    try {
      const res = await fetch(`/api/projects/${id}`, { method: "DELETE" });
      if (res.ok) fetchProjects();
    } catch (error) { console.error(error); }
  };

  const handleEditTitle = async (id: string, currentTitle: string, e: React.MouseEvent) => {
    e.stopPropagation();
    const newTitle = prompt("Nouveau titre :", currentTitle);
    if (!newTitle) return;
    try {
      const res = await fetch(`/api/projects/${id}`, { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ title: newTitle }) });
      if (res.ok) fetchProjects();
    } catch (error) { console.error(error); }
  };

  const handleIpfsSave = async () => {
    if (!textContent.trim() || isUploading) return;
    setIsUploading(true);
    try {
      const cid = await uploadTextToIPFS(textContent, "ScribUp_Draft");
      setLastCid(cid);
      setTimeout(() => setLastCid(null), 5000);
    } catch (error) { console.error(error); } finally { setIsUploading(false); }
  };

  return (
    <div className="flex h-screen overflow-hidden bg-[#020202] text-white selection:bg-[#00f2ff]">
      <aside className={`fixed md:relative inset-y-0 left-0 z-50 w-64 border-r border-white/5 bg-black/80 backdrop-blur-3xl flex flex-col transform transition-transform ${isSidebarOpen ? "translate-x-0" : "-translate-x-full"} md:translate-x-0 ${isFocusMode ? "hidden" : "flex"}`}>
        <div className="p-8 border-b border-white/5 flex items-center justify-between">
           <div className="flex items-center gap-3">
              <div className="p-2 bg-gradient-to-tr from-[#00f2ff] to-[#bc13fe] rounded-xl"><PenTool className="w-5 h-5 text-black" /></div>
              <h1 className="text-xs font-black uppercase tracking-widest">ScribUp</h1>
           </div>
        </div>
        <div className="p-4 space-y-2">
          {Object.entries(agents).map(([key, agent]) => (
            <button key={key} onClick={() => setActiveAgent(key)} className={`w-full flex items-center gap-4 p-3 rounded-2xl transition-all ${activeAgent === key ? "bg-white/5 border border-white/10" : "text-gray-500 hover:text-white"}`}>
              <div style={{ color: activeAgent === key ? agent.color : "inherit" }}>{agent.icon}</div>
              <p className="text-[9px] font-black uppercase">{agent.name}</p>
            </button>
          ))}
        </div>
        <div className="flex-1 overflow-y-auto p-4 pt-0 border-t border-white/5">
          <p className="text-[8px] uppercase font-black text-gray-600 tracking-widest my-4">Projets Cloud</p>
          {userProjects.map((proj) => (
            <div key={proj.id} onClick={() => { setTextContent(proj.content); setActiveAgent(proj.agent); }} className="w-full text-left p-3 rounded-xl hover:bg-white/5 group cursor-pointer relative">
              <p className="text-[9px] font-bold text-gray-400 group-hover:text-[#00f2ff] truncate pr-10">{proj.title}</p>
              <div className="absolute right-2 top-1/2 -translate-y-1/2 hidden group-hover:flex gap-1">
                <button onClick={(e) => handleEditTitle(proj.id, proj.title, e)} className="p-1 hover:text-[#ffd700]"><Edit3 className="w-3 h-3" /></button>
                <button onClick={(e) => handleDeleteProject(proj.id, e)} className="p-1 hover:text-red-500"><Trash2 className="w-3 h-3" /></button>
              </div>
            </div>
          ))}
        </div>
        <div className="p-6 border-t border-white/5 flex flex-col items-center scale-90"><ConnectKitButton /></div>
      </aside>

      <main className={`flex-1 flex flex-col transition-all ${isFocusMode ? "p-0" : "p-4 md:p-8"}`}>
        <header className="flex justify-between items-center mb-6">
           <div className="flex items-center gap-4">
              <button onClick={() => setIsSidebarOpen(true)} className="md:hidden p-3 bg-white/5 rounded-xl"><Menu className="w-4 h-4" /></button>
              <button onClick={() => setIsFocusMode(!isFocusMode)} className="hidden md:block p-3 bg-white/5 rounded-xl">{isFocusMode ? <Minimize2 className="w-4 h-4" /> : <Maximize2 className="w-4 h-4" />}</button>
              <h2 className="text-[10px] uppercase text-gray-500 tracking-widest">Brouillon / <span className="text-[#00f2ff]">{agents[activeAgent].name}</span></h2>
           </div>
           <div className="flex gap-2">
             <button onClick={handleRailwaySave} disabled={isSaving || !address} className="px-4 py-2 bg-[#00f2ff] text-black text-[9px] font-black uppercase rounded-lg hover:bg-white transition-all disabled:opacity-30">Save Cloud</button>
             <button onClick={handleIpfsSave} disabled={isUploading} className="px-4 py-2 bg-white text-black text-[9px] font-black uppercase rounded-lg hover:bg-[#00f2ff] transition-all disabled:opacity-30">IPFS</button>
           </div>
        </header>
        <div className="flex-1 relative">
           <textarea value={textContent} onChange={(e) => setTextContent(e.target.value)} placeholder="Diffuser vos pensÃƒÂ©es..." className="w-full h-full bg-transparent p-6 text-lg font-light outline-none resize-none z-10 relative font-serif" />
        </div>
      </main>

      <aside className={`fixed md:relative inset-y-0 right-0 z-50 w-80 border-l border-white/5 bg-black/90 flex flex-col transform transition-transform ${isAiOpen ? "translate-x-0" : "translate-x-full"} md:translate-x-0`}>
         <div className="p-6 border-b border-white/5 flex justify-between items-center">
            <h3 className="text-[10px] font-black uppercase tracking-widest">IA Mentor</h3>
            <button onClick={() => setIsAiOpen(false)} className="md:hidden"><X className="w-4 h-4" /></button>
         </div>
         <div className="flex-1 overflow-y-auto p-6 space-y-4">
            {messages.map((msg, i) => (
              <div key={i} className={`p-3 rounded-xl text-[11px] ${msg.role === "ai" ? "bg-white/5 text-gray-300" : "bg-white text-black font-bold"}`}>{msg.text}</div>
            ))}
            <div ref={chatEndRef} />
         </div>
         <div className="p-6 border-t border-white/5 bg-black/40">
            <div className="relative">
               <input value={inputMessage} onChange={(e) => setInputMessage(e.target.value)} onKeyDown={(e) => e.key === "Enter" && handleSendMessage()} placeholder="Briefer..." className="w-full bg-white/5 border border-white/10 p-4 pr-12 rounded-xl outline-none text-[11px]" />
               <button onClick={handleSendMessage} className="absolute right-2 top-1/2 -translate-y-1/2 p-2"><Send className="w-4 h-4" /></button>
            </div>
         </div>
      </aside>
      {saveSuccess && <div className="fixed top-6 left-1/2 -translate-x-1/2 z-[200] bg-[#00f2ff] text-black px-6 py-3 rounded-xl text-[10px] font-black uppercase">SauvegardÃƒÂ©</div>}
    </div>
  );
}